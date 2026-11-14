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

import dev.ohs.trustdemo.hcert.CertificateFingerprint
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.verification.ScanTransactions
import dev.ohs.trustdemo.onesa.data.verification.text
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What booking the next visit came to. */
sealed interface BookingOutcome {
  /** The visit is recommended from [date] for [doses] doses, and the referral is completed. */
  data class Booked(val date: LocalDate, val doses: Int) : BookingOutcome

  /** The referral changed since the nurse looked; nothing was written. */
  data object Changed : BookingOutcome

  /** No dose is due, so there is no visit to book. */
  data object NothingDue : BookingOutcome
}

/**
 * S5's "Book 6-Week Visit & Complete Referral": one transaction writes the recommendation for the
 * doses due, the reminder Task for the visit, and completes the referral. The ids follow from the
 * child and the due date, so booking again replaces the records rather than adding to them. The
 * referral is written only at the version just read: if it has moved, nothing is written and the
 * nurse is asked again.
 */
class VisitBooking(
  private val schedule: ScheduleService,
  private val session: () -> RegistrationSession?,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val refresh: suspend () -> Unit,
  private val clock: () -> Instant = Clock.System::now,
) {
  private val json = FhirJson.instance

  suspend fun book(referralId: String, patientId: String): BookingOutcome {
    val current = session() ?: error("Sign in again to book the visit.")
    val evaluated = schedule.evaluate(patientId)
    val due = evaluated.items.filter { it.standing is Standing.Due }
    val visit =
      due.minOfOrNull { (it.standing as Standing.Due).date } ?: return BookingOutcome.NothingDue
    val referral = read(current, referralId)
    val now = Instant.fromEpochSeconds(clock().epochSeconds)
    val recommendation = recommendationId(patientId)
    val transaction = buildJsonObject {
      put("resourceType", "Bundle")
      put("type", "transaction")
      putJsonArray("entry") {
        add(
          createEntry(
            "ImmunizationRecommendation",
            recommendation,
            recommendation(evaluated, due, current, now),
          )
        )
        add(
          createEntry(
            "Task",
            reminderId(recommendation, visit),
            reminder(patientId, recommendation, visit, due.first().age, current, now),
          )
        )
        add(
          putEntry("ServiceRequest/$referralId", completed(referral), ifMatch = version(referral))
        )
      }
    }
    val reply =
      httpClient.post(baseUrl) {
        bearerAuth(current.accessToken)
        contentType(ContentType("application", "fhir+json"))
        setBody(json.encodeToString(JsonObject.serializer(), transaction))
      }
    val outcome =
      when {
        reply.status == HttpStatusCode.PreconditionFailed ||
          reply.status == HttpStatusCode.Conflict -> BookingOutcome.Changed
        reply.status.isSuccess() -> BookingOutcome.Booked(visit, due.size)
        else ->
          error(
            "The booking was not accepted (HTTP ${reply.status.value}): ${reply.bodyAsText().take(300)}"
          )
      }
    refresh()
    return outcome
  }

  private fun recommendation(
    evaluated: Schedule,
    due: List<ScheduleItem>,
    current: RegistrationSession,
    now: Instant,
  ): JsonObject = buildJsonObject {
    put("resourceType", "ImmunizationRecommendation")
    putJsonObject("patient") { put("reference", "Patient/${evaluated.patientId}") }
    put("date", now.toString())
    putJsonObject("authority") { put("reference", "Organization/${current.organizationId}") }
    putJsonArray("recommendation") {
      due.forEachIndexed { index, item ->
        add(
          buildJsonObject {
            putJsonArray("vaccineCode") {
              add(
                buildJsonObject {
                  putJsonArray("coding") {
                    add(coding(ScanTransactions.EPI_SYSTEM, item.dose.code, item.dose.display))
                  }
                }
              )
            }
            putJsonObject("forecastStatus") {
              putJsonArray("coding") { add(coding(FORECAST_STATUS, "due", "Due")) }
            }
            putJsonArray("dateCriterion") {
              add(
                buildJsonObject {
                  putJsonObject("code") {
                    putJsonArray("coding") { add(coding(LOINC, "30980-7", "Date vaccine due")) }
                  }
                  put("value", (item.standing as Standing.Due).date.toString())
                }
              )
            }
            if (index == 0 && evaluated.counted.isNotEmpty()) {
              putJsonArray("supportingImmunization") {
                evaluated.counted.forEach {
                  add(buildJsonObject { put("reference", "Immunization/${it.id}") })
                }
              }
            }
          }
        )
      }
    }
  }

  private fun reminder(
    patientId: String,
    recommendation: String,
    visit: LocalDate,
    age: String,
    current: RegistrationSession,
    now: Instant,
  ): JsonObject = buildJsonObject {
    put("resourceType", "Task")
    put("status", "requested")
    put("intent", "plan")
    putJsonObject("code") { put("text", "Immunization appointment reminder: $age doses") }
    putJsonObject("focus") { put("reference", "ImmunizationRecommendation/$recommendation") }
    putJsonObject("for") { put("reference", "Patient/$patientId") }
    put("authoredOn", now.toString())
    putJsonObject("requester") { put("reference", "Organization/${current.organizationId}") }
    putJsonObject("restriction") {
      putJsonObject("period") {
        put("start", visit.toString())
        put("end", visit.plus(REMINDER_DAYS, DateTimeUnit.DAY).toString())
      }
    }
  }

  private suspend fun read(current: RegistrationSession, id: String): JsonObject {
    val reply = httpClient.get("$baseUrl/ServiceRequest/$id") { bearerAuth(current.accessToken) }
    check(reply.status.isSuccess()) {
      "The gateway could not read ServiceRequest/$id (HTTP ${reply.status.value})."
    }
    return json.parseToJsonElement(reply.bodyAsText()).jsonObject
  }

  private fun version(resource: JsonObject): String {
    val id = resource["meta"]?.jsonObject?.text("versionId")
    return "W/\"${checkNotNull(id) { "The gateway sent no version for ${resource.text("id")}." }}\""
  }

  private fun completed(referral: JsonObject): JsonObject {
    val meta = referral["meta"]?.jsonObject
    val kept = meta?.filterKeys { it != "versionId" && it != "lastUpdated" }.orEmpty()
    val unversioned =
      if (kept.isEmpty()) JsonObject(referral - "meta")
      else JsonObject(referral + ("meta" to JsonObject(kept)))
    return JsonObject(unversioned + ("status" to JsonPrimitive("completed")))
  }

  private fun coding(system: String, code: String, display: String): JsonObject = buildJsonObject {
    put("system", system)
    put("code", code)
    put("display", display)
  }

  companion object {
    private const val FORECAST_STATUS =
      "http://terminology.hl7.org/CodeSystem/immunization-recommendation-status"
    private const val LOINC = "http://loinc.org"
    private const val REMINDER_DAYS = 7

    /** The child's one recommendation: booking again replaces it. */
    fun recommendationId(patientId: String): String =
      ScanTransactions.idFrom(CertificateFingerprint.of("recommendation:$patientId"))

    /** One reminder per recommendation and visit date. */
    fun reminderId(recommendationId: String, visit: LocalDate): String =
      ScanTransactions.idFrom(CertificateFingerprint.of("reminder:$recommendationId:$visit"))
  }
}
