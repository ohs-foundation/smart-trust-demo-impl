/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.trustdemo.control

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.Uri
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val PAGE = "1000"
private val YEAR_DOSES =
  listOf("TrustDemoOnesaPenta1", "TrustDemoOnesaBopv1", "TrustDemoOnesaPcv1", "TrustDemoOnesaMr1")

/** The move was asked for while the story is not in a state to take it. */
class MoveRefused(message: String) : IllegalStateException(message)

/** One story date, found at [path] of [reference] on [server], and where the move puts it. */
data class DateChange(
  val reference: String,
  val path: String,
  val from: LocalDate,
  val to: LocalDate,
  val server: Server,
)

/** Everything the move would write: the date changes and the year's new doses. */
data class MovePlan(
  val shiftDays: Long,
  val changes: List<DateChange>,
  val yearDoses: List<Immunization>,
) {
  fun describe(): String = buildString {
    appendLine("Move J3 to J4.0: every story date by $shiftDays days, on HAPI and on Medplum")
    changes.forEach {
      appendLine(
        "  ${it.server.name.padEnd(7)} ${it.reference}  ${it.path}  ${it.from} -> ${it.to}"
      )
    }
    if (yearDoses.isNotEmpty()) {
      appendLine("Year doses, new on HAPI")
      yearDoses
        .map { it.fields() }
        .forEach { appendLine("  ${it.code()}  ${it.text("occurrenceDateTime")}  ${it.patient()}") }
    }
  }

  private fun JsonObject.code() =
    getValue("vaccineCode").jsonObject.getValue("coding").jsonArray.first().jsonObject.text("code")

  private fun JsonObject.patient() = getValue("patient").jsonObject.text("reference")
}

/**
 * The one supported time move, J3 to J4.0, and the consent expiry. The move shifts every story date
 * on both servers by the same days, leaving `meta`, the cards and the base records alone.
 */
class TimeMove(
  private val states: StoryStates,
  private val onesa: FhirServer,
  private val kesaro: MedplumAdmin,
) {
  suspend fun plan(withYearDoses: Boolean): MovePlan {
    val state = states.state()
    if (state.moved) {
      throw MoveRefused(
        "Already moved: Somsak's birth date on Onesa is ${state.dates.birthDate}, " +
          "the reset day's less $MOVE_DAYS days"
      )
    }
    val step = StepEngine.currentStep(state)
    if (step != null && step < Step.YEAR) {
      throw MoveRefused("J3 is not complete: the story is at ${step.code} · ${step.title}")
    }
    val changes =
      listOf(Server.MEDPLUM, Server.HAPI).flatMap { server ->
        StoryDates.paths.keys.flatMap { type ->
          records(server, type).filterNot(StoryDates::isBase).flatMap { changesIn(server, it) }
        }
      }
    val doses = if (withYearDoses) yearDoses(state.shiftDays() - MOVE_DAYS) else emptyList()
    return MovePlan(-MOVE_DAYS, changes, doses)
  }

  /**
   * Writes each changed record once, Medplum then HAPI with Somsak's patient last, then the doses.
   */
  suspend fun apply(plan: MovePlan) {
    val byRecord = plan.changes.groupBy { it.server to it.reference }
    listOf(Server.MEDPLUM, Server.HAPI).forEach { server ->
      byRecord
        .filterKeys { it.first == server }
        .entries
        .sortedBy { it.key.second.startsWith("Patient/") }
        .forEach { (key, changes) ->
          val moved =
            changes.fold(fetch(server, key.second)) { record, change ->
              StoryDates.shifted(record, change.path, change.from, plan.shiftDays)
            }
          store(server, moved)
        }
    }
    if (plan.yearDoses.isNotEmpty()) onesa.transaction(create(plan.yearDoses))
  }

  /** Ends every active consent on HAPI the day before [today], returning the ones ended. */
  suspend fun expireConsents(today: LocalDate): List<String> =
    onesa.search("Consent", mapOf("status" to "active", "_count" to PAGE)).map { consent ->
      val record = consent.fields()
      val provision = record["provision"]?.jsonObject ?: JsonObject(emptyMap())
      val period = provision["period"]?.jsonObject ?: JsonObject(emptyMap())
      val ended = period + ("end" to JsonPrimitive(today.minusDays(1).toString()))
      store(
        Server.HAPI,
        JsonObject(
          record + ("provision" to JsonObject(provision + ("period" to JsonObject(ended))))
        ),
      )
      consent.ref()
    }

  private suspend fun records(server: Server, type: String): List<JsonObject> =
    when (server) {
      Server.HAPI -> onesa.search(type, mapOf("_count" to PAGE)).map { it.fields() }
      Server.MEDPLUM -> kesaro.searchJson(type, mapOf("_count" to PAGE))
    }

  private suspend fun fetch(server: Server, reference: String): JsonObject {
    val type = reference.substringBefore('/')
    val params = mapOf("_id" to reference.substringAfter('/'))
    return when (server) {
      Server.HAPI -> onesa.search(type, params).single().fields()
      Server.MEDPLUM -> kesaro.searchJson(type, params).single()
    }
  }

  private suspend fun store(server: Server, record: JsonObject) {
    when (server) {
      Server.HAPI -> onesa.update(Json.decodeFromJsonElement(Resource.serializer(), record))
      Server.MEDPLUM -> kesaro.replace(record)
    }
  }

  private fun changesIn(server: Server, record: JsonObject): List<DateChange> =
    StoryDates.datesIn(record).map { (path, value) ->
      val reference = "${record.text("resourceType")}/${record.text("id")}"
      val to = StoryDates.dateOf(StoryDates.shift(value, -MOVE_DAYS))
      DateChange(reference, path, StoryDates.dateOf(value), to, server)
    }

  private suspend fun yearDoses(days: Long): List<Immunization> {
    val somsak =
      onesa.search("Patient", mapOf("family" to "Sengmany", "given" to "Somsak")).firstOrNull()
        ?: throw MoveRefused("Somsak is not on Onesa")
    val patient = buildJsonObject { put("reference", "Patient/${somsak.id}") }
    return IgResources.all()
      .filterIsInstance<Immunization>()
      .filter { it.id in YEAR_DOSES }
      .sortedBy { YEAR_DOSES.indexOf(it.id) }
      .map { dose ->
        val record = dose.fields()
        val date = StoryDates.shift(record.text("occurrenceDateTime")!!, days)
        Json.decodeFromJsonElement(
          Resource.serializer(),
          JsonObject(
            record - "id" + mapOf("patient" to patient, "occurrenceDateTime" to JsonPrimitive(date))
          ),
        ) as Immunization
      }
  }

  private fun create(doses: List<Immunization>) =
    Bundle(
      type = Enumeration(value = Bundle.BundleType.Transaction),
      entry =
        doses.map {
          Bundle.Entry(
            resource = it,
            request =
              Bundle.Entry.Request(
                method = Enumeration(value = Bundle.HTTPVerb.Post),
                url = Uri(value = "Immunization"),
              ),
          )
        },
    )
}
