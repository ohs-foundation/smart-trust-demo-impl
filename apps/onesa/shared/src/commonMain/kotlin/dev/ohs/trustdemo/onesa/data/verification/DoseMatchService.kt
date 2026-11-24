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
package dev.ohs.trustdemo.onesa.data.verification

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.schedule.Antigens
import dev.ohs.trustdemo.onesa.data.schedule.Schedule
import dev.ohs.trustdemo.onesa.data.sync.succeeded
import dev.ohs.trustdemo.onesa.generateId
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.onesa.util.createEntry
import dev.ohs.trustdemo.onesa.util.putEntry
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A national schedule dose the nurse can say a product counts as. */
data class EpiDose(val code: String, val display: String)

/**
 * A verified dose whose product the national map does not name: DT.02 asks the nurse. [given] is
 * its date, when the record gives the day.
 */
data class UnmatchedDose(
  val id: String,
  val patientId: String,
  val product: String,
  val label: String,
  val given: LocalDate?,
)

/** What the nurse's choice came to. */
sealed interface MatchOutcome {
  /** The dose carries [dose], and the national map names it for the product. */
  data class Matched(val dose: EpiDose, val listStale: Boolean = false) : MatchOutcome

  /** The map or the dose changed since she looked; nothing was written. */
  data object Changed : MatchOutcome
}

/**
 * DT.02 rule 3. When a verified dose's product has no schedule code in the national map, the nurse
 * says which dose it counts as. One transaction then adds the pair to the map, adds the code beside
 * the issuer's product code (which stays), and records her adjudication. The map is national, so
 * the map and the dose are written only at the version just read: if either has moved, nothing is
 * written and she is asked again.
 *
 * Rule 1 (a reported dose is not imported) and rule 2 (the map has the product) never reach the
 * picker; rule 4 (she says none fits) writes nothing and the dose stays unmapped.
 */
class DoseMatchService(
  private val repository: FhirRepository,
  private val session: suspend () -> RegistrationSession?,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val refresh: suspend () -> Boolean,
  private val clock: () -> Instant = Clock.System::now,
  private val newId: () -> String = ::generateId,
  private val loadAntigens: suspend () -> Antigens = Antigens::load,
) {
  private val json = FhirJson.instance
  private val national = NationalMap(repository)
  private var antigens: Antigens? = null

  /** The verified doses of [patientId] that need the picker. */
  suspend fun unmatched(patientId: String): List<UnmatchedDose> {
    val mapped = national.epiCodes()
    return repository
      .all("Immunization")
      .map { it.toJson() }
      .filter {
        it.reference("patient") == "Patient/$patientId" && it.text("primarySource") == "true"
      }
      .mapNotNull { dose ->
        val codings = dose.codings()
        val id = dose.text("id")
        val product =
          codings.firstOrNull { it.text("system") == ScanTransactions.PRODUCT_SYSTEM }?.text("code")
        val hasSchedule = codings.any { it.text("system") == ScanTransactions.EPI_SYSTEM }
        if (id == null || product == null || product in mapped || hasSchedule)
          return@mapNotNull null
        val date = dose.text("occurrenceDateTime")?.substringBefore('T')
        val name = NewbornRegistration.productName(product) ?: product
        UnmatchedDose(
          id,
          patientId,
          product,
          listOfNotNull(name, date).joinToString(", "),
          date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
      }
  }

  /** The picker's choices: the doses of the synced national schedule code system. */
  suspend fun choices(): List<EpiDose> =
    national.scheduleDisplays().map { (code, display) -> EpiDose(code, display) }

  /**
   * The choice put to the nurse for [dose]: the [schedule] dose of the product's antigens due at
   * the child's age when it was given, or null when none is. It is a proposal; nothing is written
   * until she confirms it.
   */
  suspend fun proposal(dose: UnmatchedDose, choices: List<EpiDose>, schedule: Schedule): EpiDose? {
    val given = dose.given ?: return null
    val table = antigens ?: loadAntigens().also { antigens = it }
    val code = table.doseFor(dose.product, given, schedule) ?: return null
    return choices.firstOrNull { it.code == code }
  }

  /** Records that [dose] counts as [choice], unless the map or the dose has changed meanwhile. */
  suspend fun confirm(dose: UnmatchedDose, choice: EpiDose): MatchOutcome {
    val current = session() ?: error("Sign in again to match a dose.")
    val mapId = national.id() ?: error("The national map has not synced yet.")
    val map = read(current, "ConceptMap", mapId)
    val immunization = read(current, "Immunization", dose.id)
    val carried =
      immunization.codings().firstOrNull { it.text("system") == ScanTransactions.EPI_SYSTEM }
    val outcome =
      when {
        carried != null ->
          if (carried.text("code") == choice.code) MatchOutcome.Matched(choice)
          else MatchOutcome.Changed
        dose.product in epiCodesOf(map) -> MatchOutcome.Changed
        else -> write(current, dose, choice, map, immunization)
      }
    val synced = succeeded(refresh)
    return if (outcome is MatchOutcome.Matched) outcome.copy(listStale = !synced) else outcome
  }

  private suspend fun write(
    current: RegistrationSession,
    dose: UnmatchedDose,
    choice: EpiDose,
    map: JsonObject,
    immunization: JsonObject,
  ): MatchOutcome {
    val now = clock()
    val mapId = checkNotNull(map.text("id")) { "The national map has no id." }
    val transaction = buildJsonObject {
      put("resourceType", "Bundle")
      put("type", "transaction")
      putJsonArray("entry") {
        add(
          putEntry(
            "ConceptMap/$mapId",
            withElement(map, dose.product, choice, now),
            ifMatch = version(map),
          )
        )
        add(
          putEntry(
            "Immunization/${dose.id}",
            withSchedule(immunization, choice),
            ifMatch = version(immunization),
          )
        )
        add(createEntry("Provenance", newId(), adjudication(current, dose, choice, mapId, now)))
      }
    }
    val reply =
      httpClient.post(baseUrl) {
        bearerAuth(current.accessToken)
        contentType(ContentType("application", "fhir+json"))
        setBody(json.encodeToString(JsonObject.serializer(), transaction))
      }
    return when {
      reply.status == HttpStatusCode.PreconditionFailed ||
        reply.status == HttpStatusCode.Conflict -> MatchOutcome.Changed
      reply.status.isSuccess() -> MatchOutcome.Matched(choice)
      else ->
        error(
          "The match was not accepted (HTTP ${reply.status.value}): ${reply.bodyAsText().take(300)}"
        )
    }
  }

  private suspend fun read(current: RegistrationSession, type: String, id: String): JsonObject {
    val reply = httpClient.get("$baseUrl/$type/$id") { bearerAuth(current.accessToken) }
    check(reply.status.isSuccess()) {
      "The gateway could not read $type/$id (HTTP ${reply.status.value})."
    }
    return json.parseToJsonElement(reply.bodyAsText()).jsonObject
  }

  private fun version(resource: JsonObject): String {
    val id = resource["meta"]?.jsonObject?.text("versionId")
    return "W/\"${checkNotNull(id) { "The gateway sent no version for ${resource.text("id")}." }}\""
  }

  /** [map] with the product's element added to the product-to-schedule group. */
  private fun withElement(
    map: JsonObject,
    product: String,
    choice: EpiDose,
    now: Instant,
  ): JsonObject {
    val element = buildJsonObject {
      put("code", product)
      NewbornRegistration.productDisplay(product)?.let { put("display", it) }
      putJsonArray("target") {
        add(
          buildJsonObject {
            put("code", choice.code)
            put("display", choice.display)
            put("equivalence", "equivalent")
            put(
              "comment",
              "Added from the nurse's adjudication on ${now.toString().substringBefore('T')}.",
            )
          }
        )
      }
    }
    val groups = map["group"]?.jsonArray.orEmpty().map { it.jsonObject }
    val fits = { group: JsonObject ->
      group.text("source") == ScanTransactions.PRODUCT_SYSTEM &&
        group.text("target") == ScanTransactions.EPI_SYSTEM
    }
    val updated =
      if (groups.any(fits)) {
        groups.map {
          if (fits(it))
            JsonObject(it + ("element" to JsonArray(it["element"]?.jsonArray.orEmpty() + element)))
          else it
        }
      } else {
        groups +
          buildJsonObject {
            put("source", ScanTransactions.PRODUCT_SYSTEM)
            put("target", ScanTransactions.EPI_SYSTEM)
            putJsonArray("element") { add(element) }
          }
      }
    return unversioned(JsonObject(map + ("group" to JsonArray(updated))))
  }

  /** [immunization] with the schedule code in front of the codings it already has. */
  private fun withSchedule(immunization: JsonObject, choice: EpiDose): JsonObject {
    val code = immunization.getValue("vaccineCode").jsonObject
    val schedule = buildJsonObject {
      put("system", ScanTransactions.EPI_SYSTEM)
      put("code", choice.code)
      put("display", choice.display)
    }
    val codings = JsonArray(listOf(schedule) + code["coding"]?.jsonArray.orEmpty())
    val named =
      JsonObject(code + mapOf("coding" to codings, "text" to JsonPrimitive(choice.display)))
    return unversioned(JsonObject(immunization + ("vaccineCode" to named)))
  }

  private fun unversioned(resource: JsonObject): JsonObject {
    val meta = resource["meta"]?.jsonObject ?: return resource
    val kept = meta.filterKeys { it != "versionId" && it != "lastUpdated" }
    return if (kept.isEmpty()) JsonObject(resource - "meta")
    else JsonObject(resource + ("meta" to JsonObject(kept)))
  }

  /**
   * Targets the Patient too: the gateway scopes a Provenance to a clinic only by the Patient it
   * names. The IG has no profile for an adjudication, so this follows `TrustDemoJ3Adjudication`.
   */
  private fun adjudication(
    current: RegistrationSession,
    dose: UnmatchedDose,
    choice: EpiDose,
    mapId: String,
    now: Instant,
  ): JsonObject = buildJsonObject {
    put("resourceType", "Provenance")
    putJsonArray("target") {
      listOf("Immunization/${dose.id}", "ConceptMap/$mapId", "Patient/${dose.patientId}").forEach {
        add(buildJsonObject { put("reference", it) })
      }
    }
    put("recorded", now.toString())
    putJsonObject("activity") {
      put(
        "text",
        "DT.02: confirmed ${dose.product} as equivalent to ${choice.code}; " +
          "issuer coding kept, schedule coding added",
      )
    }
    putJsonArray("agent") {
      add(
        buildJsonObject {
          putJsonObject("type") {
            putJsonArray("coding") {
              add(
                buildJsonObject {
                  put("system", "http://terminology.hl7.org/CodeSystem/provenance-participant-type")
                  put("code", "attester")
                  put("display", "Attester")
                }
              )
            }
          }
          putJsonObject("who") { put("reference", "Practitioner/${current.practitionerId}") }
        }
      )
    }
  }

  private fun JsonObject.reference(name: String): String? =
    this[name]?.jsonObject?.text("reference")

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject
}
