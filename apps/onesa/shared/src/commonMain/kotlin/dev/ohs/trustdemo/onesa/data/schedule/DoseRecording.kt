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
package dev.ohs.trustdemo.onesa.data.schedule

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.sync.succeeded
import dev.ohs.trustdemo.onesa.data.verification.EpiDose
import dev.ohs.trustdemo.onesa.data.verification.NationalMap
import dev.ohs.trustdemo.onesa.data.verification.ScanTransactions
import dev.ohs.trustdemo.onesa.data.verification.text
import dev.ohs.trustdemo.onesa.generateId
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.onesa.util.createEntry
import dev.ohs.trustdemo.onesa.util.profile
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the nurse typed for a dose given at the clinic. */
data class DoseEntry(
  val patientId: String,
  val dose: EpiDose,
  val product: String?,
  val lotNumber: String,
  val date: LocalDate,
)

/** Why an entry cannot be recorded. */
enum class DoseProblem {
  LotNumberMissing,
  DateInTheFuture,
  DateBeforeBirth,
}

/** What recording the dose came to. */
sealed interface RecordOutcome {
  /** The dose is written; when [listStale] the local copy could not be refreshed. */
  data class Recorded(val listStale: Boolean = false) : RecordOutcome

  /** The last write has not come back from the server yet; the dose is not checked or written. */
  data object SyncFirst : RecordOutcome

  /** The entry is not one that could have happened; nothing was written. */
  data class Refused(val problem: DoseProblem) : RecordOutcome

  /** The dose already counts, given on [date]; nothing is written until the nurse confirms. */
  data class AlreadyRecorded(val date: LocalDate) : RecordOutcome
}

/**
 * The nurse's own record of a dose given at the clinic: an Immunization with `primarySource` true,
 * shaped as the IG's `TrustDemoOnesaImmunization`, written by one transaction under a new id. A
 * dose with the same schedule code that already counts (TD.DT.03) is written only after she
 * confirms, because a repeat dose is real.
 */
class DoseRecording(
  private val repository: FhirRepository,
  private val choices: suspend () -> List<EpiDose>,
  private val session: suspend () -> RegistrationSession?,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val refresh: suspend () -> Boolean,
  private val caughtUp: () -> Boolean,
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
  private val newId: () -> String = ::generateId,
) {
  private val json = FhirJson.instance

  /** The schedule doses the nurse can pick, from the synced national code system. */
  suspend fun doses(): List<EpiDose> = choices()

  /**
   * The products the national map names for [dose]; none means the schedule code is recorded alone.
   */
  suspend fun products(dose: EpiDose): List<EpiDose> {
    val names = productNames()
    return NationalMap(repository)
      .epiCodes()
      .filterValues { it == dose.code }
      .keys
      .map { EpiDose(it, names[it] ?: NewbornRegistration.PRODUCTS[it] ?: it) }
  }

  /**
   * Writes the dose, unless the entry is refused or it repeats a counted dose not yet [confirmed].
   */
  suspend fun record(entry: DoseEntry, confirmed: Boolean = false): RecordOutcome {
    val current = session() ?: error("Sign in again to record the dose.")
    val birth = birthDate(entry.patientId)
    val problem =
      when {
        entry.lotNumber.isBlank() -> DoseProblem.LotNumberMissing
        entry.date > today() -> DoseProblem.DateInTheFuture
        entry.date < birth -> DoseProblem.DateBeforeBirth
        else -> null
      }
    if (problem != null) return RecordOutcome.Refused(problem)
    if (!confirmed) {
      if (!caughtUp()) return RecordOutcome.SyncFirst
      counted(entry)?.let {
        return RecordOutcome.AlreadyRecorded(it)
      }
    }

    val id = newId()
    val dose = immunization(entry, current)
    val transaction = buildJsonObject {
      put("resourceType", "Bundle")
      put("type", "transaction")
      putJsonArray("entry") { add(createEntry("Immunization", id, dose)) }
    }
    val reply =
      httpClient.post(baseUrl) {
        bearerAuth(current.accessToken)
        contentType(ContentType("application", "fhir+json"))
        setBody(json.encodeToString(JsonObject.serializer(), transaction))
      }
    check(reply.status.isSuccess()) {
      "The dose was not accepted (HTTP ${reply.status.value}): ${reply.bodyAsText().take(300)}"
    }
    return RecordOutcome.Recorded(!succeeded(refresh))
  }

  private suspend fun immunization(entry: DoseEntry, current: RegistrationSession): JsonObject {
    val names = productNames()
    val clinic = organisationName(current)
    return buildJsonObject {
      put("resourceType", "Immunization")
      put("meta", profile("TrustDemoOnesaImmunization"))
      put("status", "completed")
      putJsonObject("vaccineCode") {
        putJsonArray("coding") {
          add(coding(ScanTransactions.EPI_SYSTEM, entry.dose.code, entry.dose.display))
          entry.product?.let {
            add(
              coding(
                ScanTransactions.PRODUCT_SYSTEM,
                it,
                names[it] ?: NewbornRegistration.PRODUCTS[it],
              )
            )
          }
        }
      }
      put("primarySource", true)
      putJsonObject("patient") { put("reference", "Patient/${entry.patientId}") }
      put("occurrenceDateTime", entry.date.toString())
      putJsonArray("performer") {
        add(
          buildJsonObject {
            putJsonObject("actor") { put("reference", "Practitioner/${current.practitionerId}") }
          }
        )
      }
      put("lotNumber", entry.lotNumber.trim())
      clinic?.let { putJsonObject("location") { put("display", it) } }
      putJsonArray("protocolApplied") {
        add(buildJsonObject { put("doseNumberPositiveInt", doseNumber(entry.dose.code)) })
      }
    }
  }

  /** The date of a completed, verified, dated dose that already carries [entry]'s schedule code. */
  private suspend fun counted(entry: DoseEntry): LocalDate? =
    repository
      .all("Immunization")
      .map { it.toJson() }
      .filter {
        it["patient"]?.jsonObject?.text("reference") == "Patient/${entry.patientId}" &&
          it.text("status") == "completed" &&
          it.text("primarySource") == "true"
      }
      .filter { dose ->
        dose["vaccineCode"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().any {
          it.jsonObject.text("system") == ScanTransactions.EPI_SYSTEM &&
            it.jsonObject.text("code") == entry.dose.code
        }
      }
      .mapNotNull { it.text("occurrenceDateTime")?.take(DATE_LENGTH)?.let(LocalDate::parse) }
      .minOrNull()

  private suspend fun birthDate(patientId: String): LocalDate {
    val patient =
      repository.get("Patient", patientId)?.toJson()
        ?: error("The child's record is not on this device.")
    return LocalDate.parse(
      checkNotNull(patient.text("birthDate")?.takeIf { it.length == DATE_LENGTH }) {
        "The child's date of birth is not known to the day."
      }
    )
  }

  private suspend fun productNames(): Map<String, String> =
    repository
      .all("CodeSystem")
      .map { it.toJson() }
      .filter { it.text("url") == ScanTransactions.PRODUCT_SYSTEM }
      .flatMap { it["concept"]?.jsonArray.orEmpty() }
      .mapNotNull { concept ->
        val code = concept.jsonObject.text("code") ?: return@mapNotNull null
        concept.jsonObject.text("display")?.let { code to it }
      }
      .toMap()

  private suspend fun organisationName(current: RegistrationSession): String? =
    repository.get("Organization", current.organizationId)?.toJson()?.text("name")

  private fun coding(system: String, code: String, display: String?): JsonObject = buildJsonObject {
    put("system", system)
    put("code", code)
    display?.let { put("display", it) }
  }

  private fun doseNumber(code: String): Int =
    Regex("-(\\d+)$").find(code)?.groupValues?.get(1)?.toIntOrNull() ?: 1

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject

  private companion object {
    const val DATE_LENGTH = 10
  }
}
