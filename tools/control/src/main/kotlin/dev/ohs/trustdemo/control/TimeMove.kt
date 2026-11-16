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
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val PAGE = "1000"

// Taifafeki's only DocumentReferences are Somsak's cards, whose signed dates are the story clock.
private const val CARD_TYPE = "DocumentReference"
private const val EXPLICIT_CONSENT =
  "http://smart.who.int/trust-phw/StructureDefinition/PHWExplicitConsent"
private val YEAR_DOSES =
  listOf("TrustDemoOnesaPenta1", "TrustDemoOnesaBopv1", "TrustDemoOnesaPcv1", "TrustDemoOnesaMr1")
private const val FORECAST_STATUS =
  "http://terminology.hl7.org/CodeSystem/immunization-recommendation-status"

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

/**
 * Everything the move would write: the date changes, the year's new doses, and the booked visit
 * records they fulfil ([fulfilled]), which the move completes.
 */
data class MovePlan(
  val shiftDays: Long,
  val changes: List<DateChange>,
  val yearDoses: List<Immunization>,
  val fulfilled: List<String> = emptyList(),
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
    if (fulfilled.isNotEmpty()) {
      appendLine("Booked visit the year doses fulfil, completed on HAPI")
      fulfilled.forEach { appendLine("  $it") }
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
  private val clock: Clock = Clock.systemDefaultZone(),
) {
  suspend fun plan(withYearDoses: Boolean): MovePlan {
    val state = states.state()
    val servers =
      listOfNotNull(
        Server.MEDPLUM.takeUnless { state.movedOnKesaro },
        Server.HAPI.takeUnless { state.movedOnOnesa },
      )
    if (servers.isEmpty()) {
      throw MoveRefused(
        "Already moved: Somsak's birth date is ${state.dates.birthDate} on both servers, " +
          "the reset day's less $MOVE_DAYS days"
      )
    }
    val step = StepEngine.currentStep(state)
    if (step != null && step < Step.YEAR) {
      throw MoveRefused("J3 is not complete: the story is at ${step.code} · ${step.title}")
    }
    val shares = onesa.searchAll("Consent", mapOf("_profile" to EXPLICIT_CONSENT, "_count" to PAGE))
    if (shares.isNotEmpty()) {
      throw MoveRefused(
        "${shares.size} shared consents exist; shares belong after the move: " +
          "withdraw the shares or reset first"
      )
    }
    val changes =
      servers.flatMap { server ->
        StoryDates.paths.keys
          .filterNot { server == Server.MEDPLUM && it == CARD_TYPE }
          .flatMap { type ->
            records(server, type).filterNot(StoryDates::isBase).flatMap { changesIn(server, it) }
          }
      }
    val doses =
      if (withYearDoses && Server.HAPI in servers) yearDoses(state.shiftDays() - MOVE_DAYS)
      else emptyList()
    return MovePlan(-MOVE_DAYS, changes, doses, fulfilledVisit(doses))
  }

  /**
   * Writes one transaction per server, Medplum then HAPI with the year doses and the visit they
   * fulfil in HAPI's, so a server is either moved whole or untouched and a rerun finishes only the
   * server still to move.
   */
  suspend fun apply(plan: MovePlan) {
    val byServer = plan.changes.groupBy { it.server }
    byServer[Server.MEDPLUM]?.let { kesaro.replaceAll(moved(Server.MEDPLUM, it, plan.shiftDays)) }
    val shifted = byServer[Server.HAPI]?.let { moved(Server.HAPI, it, plan.shiftDays) }.orEmpty()
    val given = plan.yearDoses.flatMap { it.codes() }.toSet()
    val completed =
      plan.fulfilled.map { reference ->
        val record =
          shifted.firstOrNull { it.reference() == reference } ?: fetch(Server.HAPI, reference)
        completed(record, given)
      }
    val updated = shifted.filterNot { it.reference() in plan.fulfilled } + completed
    if (updated.isNotEmpty() || plan.yearDoses.isNotEmpty()) {
      onesa.transaction(
        transaction(
          updated.map { Json.decodeFromJsonElement(Resource.serializer(), it) },
          plan.yearDoses,
        )
      )
    }
  }

  /** Ends every active consent on HAPI a minute before now, returning the ones ended. */
  suspend fun expireConsents(): List<String> {
    val end =
      ZonedDateTime.now(clock)
        .minusMinutes(1)
        .truncatedTo(ChronoUnit.SECONDS)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    return onesa.searchAll("Consent", mapOf("status" to "active", "_count" to PAGE)).map { consent
      ->
      val record = consent.fields()
      val provision = record["provision"]?.jsonObject ?: JsonObject(emptyMap())
      val period = provision["period"]?.jsonObject ?: JsonObject(emptyMap())
      val ended = period + ("end" to JsonPrimitive(end))
      onesa.update(
        Json.decodeFromJsonElement(
          Resource.serializer(),
          JsonObject(
            record + ("provision" to JsonObject(provision + ("period" to JsonObject(ended))))
          ),
        )
      )
      consent.ref()
    }
  }

  private suspend fun moved(server: Server, changes: List<DateChange>, days: Long) =
    changes
      .groupBy { it.reference }
      .map { (reference, ofRecord) ->
        ofRecord.fold(fetch(server, reference)) { record, change ->
          StoryDates.shifted(record, change.path, change.from, days)
        }
      }

  private suspend fun records(server: Server, type: String): List<JsonObject> =
    when (server) {
      Server.HAPI -> onesa.searchAll(type, mapOf("_count" to PAGE)).map { it.fields() }
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

  /**
   * Somsak's booked visit that [doses] fulfil, as a recommendation is a forecast: each
   * recommendation with a dose among them, and its requested reminder once every dose it books is
   * given.
   */
  private suspend fun fulfilledVisit(doses: List<Immunization>): List<String> {
    val patient =
      doses.firstOrNull()?.fields()?.get("patient")?.jsonObject?.text("reference")
        ?: return emptyList()
    val given = doses.flatMap { it.codes() }.toSet()
    val reminders =
      onesa.searchAll("Task", mapOf("status" to "requested", "_count" to PAGE)).map { it.fields() }
    return onesa
      .searchAll(
        "ImmunizationRecommendation",
        mapOf("patient" to patient.removePrefix("Patient/"), "_count" to PAGE),
      )
      .map { it.fields() }
      .filter { recommendation ->
        recommendation.entries().any { !it.complete() && it.code() in given }
      }
      .flatMap { recommendation ->
        val reference = recommendation.reference()
        val done = recommendation.entries().all { it.complete() || it.code() in given }
        listOf(reference) +
          reminders
            .filter { done && it["focus"]?.jsonObject?.text("reference") == reference }
            .map { it.reference() }
      }
  }

  private fun completed(record: JsonObject, given: Set<String>): JsonObject =
    when (record.text("resourceType")) {
      "Task" -> JsonObject(record + ("status" to JsonPrimitive("completed")))
      else ->
        JsonObject(
          record +
            ("recommendation" to
              JsonArray(
                record.entries().map { entry ->
                  if (entry.code() in given) JsonObject(entry + ("forecastStatus" to complete))
                  else entry
                }
              ))
        )
    }

  private val complete = buildJsonObject {
    putJsonArray("coding") {
      add(
        buildJsonObject {
          put("system", FORECAST_STATUS)
          put("code", "complete")
          put("display", "Complete")
        }
      )
    }
  }

  private fun Immunization.codes(): List<String> =
    fields()["vaccineCode"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().mapNotNull {
      it.jsonObject.text("code")
    }

  private fun JsonObject.reference() = "${text("resourceType")}/${text("id")}"

  private fun JsonObject.entries(): List<JsonObject> =
    this["recommendation"]?.jsonArray.orEmpty().map { it.jsonObject }

  private fun JsonObject.code(): String? =
    this["vaccineCode"]
      ?.jsonArray
      ?.firstOrNull()
      ?.jsonObject
      ?.get("coding")
      ?.jsonArray
      ?.firstOrNull()
      ?.jsonObject
      ?.text("code")

  private fun JsonObject.complete(): Boolean =
    this["forecastStatus"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().any {
      it.jsonObject.text("code") == "complete"
    }

  private fun transaction(updated: List<Resource>, doses: List<Immunization>) =
    Bundle(
      type = Enumeration(value = Bundle.BundleType.Transaction),
      entry =
        updated.map { entry(it, Bundle.HTTPVerb.Put, "${it.resourceType()}/${it.id}") } +
          doses.map { entry(it, Bundle.HTTPVerb.Post, "Immunization") },
    )

  private fun entry(resource: Resource, method: Bundle.HTTPVerb, url: String) =
    Bundle.Entry(
      resource = resource,
      request = Bundle.Entry.Request(method = Enumeration(value = method), url = Uri(value = url)),
    )
}
