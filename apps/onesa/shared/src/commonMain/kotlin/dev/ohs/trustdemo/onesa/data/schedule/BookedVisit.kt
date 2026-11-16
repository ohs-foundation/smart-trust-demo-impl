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
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.repository.personName
import dev.ohs.trustdemo.onesa.data.verification.text
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.onesa.util.putEntry
import dev.ohs.trustdemo.onesa.util.unversioned
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A dose the child's current recommendation names, its schedule [code], and the date it is due. */
data class RecommendedDose(val name: String, val due: String?, val code: String? = null)

/** The child's current recommendation and the reminder ([reminderId]) for its visit. */
data class BookedVisit(
  val doses: List<RecommendedDose>,
  val reminderFrom: String?,
  val reminderTo: String?,
  val reminderId: String? = null,
)

/** A visit the clinic has booked: whose, and the [visit] itself. */
data class ClinicVisit(val patientId: String, val patientName: String, val visit: BookedVisit)

/**
 * Reads booked visits from the synced copy: a child's, for the profile, and the clinic's. A
 * recommendation is a forecast, so a dose the child has been given since leaves its visit, and a
 * visit with no dose left is no longer booked.
 */
class BookedVisits(private val repository: FhirRepository) {
  private val json = FhirJson.instance

  suspend fun of(patientId: String): BookedVisit? =
    recommendation(patientId)?.let { visit(it, given()[patientId].orEmpty()) }

  /** Every visit with a reminder still requested and a dose still to give, soonest first. */
  suspend fun all(): List<ClinicVisit> {
    val given = given()
    return records("ImmunizationRecommendation")
      .mapNotNull { recommendation ->
        val patientId =
          recommendation["patient"]?.jsonObject?.text("reference")?.removePrefix("Patient/")
            ?: return@mapNotNull null
        val visit =
          visit(recommendation, given[patientId].orEmpty())?.takeIf { it.reminderId != null }
            ?: return@mapNotNull null
        ClinicVisit(patientId, patientName(patientId), visit)
      }
      .sortedBy { it.visit.reminderFrom }
  }

  /**
   * What giving [code] does to the child's booked visit, as transaction entries: the recommendation
   * with each dose now given marked complete, and its reminder completed once no dose is left. None
   * when the visit is not waiting for [code].
   */
  suspend fun doseGiven(patientId: String, code: String): List<JsonObject> {
    val recommendation = recommendation(patientId) ?: return emptyList()
    val entries = recommendation["recommendation"]?.jsonArray.orEmpty().map { it.jsonObject }
    if (entries.none { it.code() == code && !it.complete() }) return emptyList()
    val given = given()[patientId].orEmpty() + code
    val marked = entries.map { if (it.code() in given) it.completed() else it }
    val id = recommendation.text("id")
    val reminder = reminder(id)?.takeIf { marked.all { entry -> entry.complete() } }
    return listOfNotNull(
      putEntry(
        "ImmunizationRecommendation/$id",
        JsonObject(recommendation.unversioned() + ("recommendation" to JsonArray(marked))),
      ),
      reminder?.let {
        putEntry(
          "Task/${it.text("id")}",
          JsonObject(it.unversioned() + ("status" to JsonPrimitive("completed"))),
        )
      },
    )
  }

  private suspend fun recommendation(patientId: String): JsonObject? =
    records("ImmunizationRecommendation").firstOrNull {
      it["patient"]?.jsonObject?.text("reference") == "Patient/$patientId"
    }

  private suspend fun reminder(recommendationId: String?): JsonObject? =
    records("Task").firstOrNull {
      it["focus"]?.jsonObject?.text("reference") ==
        "ImmunizationRecommendation/$recommendationId" && it.text("status") == "requested"
    }

  /** The schedule codes of each child's completed doses. */
  private suspend fun given(): Map<String, Set<String>> =
    records("Immunization")
      .filter { it.text("status") == "completed" }
      .groupBy { it["patient"]?.jsonObject?.text("reference")?.removePrefix("Patient/") }
      .mapNotNull { (patientId, doses) ->
        patientId?.let {
          it to
            doses
              .flatMap { dose ->
                dose["vaccineCode"]?.jsonObject?.get("coding")?.jsonArray.orEmpty()
              }
              .mapNotNull { coding -> coding.jsonObject.text("code") }
              .toSet()
        }
      }
      .toMap()

  private suspend fun visit(recommendation: JsonObject, given: Set<String>): BookedVisit? {
    val reminder = reminder(recommendation.text("id"))
    val period = reminder?.get("restriction")?.jsonObject?.get("period")?.jsonObject
    val doses =
      recommendation["recommendation"]
        ?.jsonArray
        .orEmpty()
        .map { it.jsonObject }
        .filterNot { it.complete() || it.code() in given }
        .map { entry ->
          val coding = entry.coding()
          val due = entry["dateCriterion"]?.jsonArray?.firstOrNull()?.jsonObject
          RecommendedDose(
            name = coding?.text("display") ?: coding?.text("code").orEmpty(),
            due = due?.text("value"),
            code = coding?.text("code"),
          )
        }
    if (doses.isEmpty()) return null
    return BookedVisit(
      doses = doses,
      reminderFrom = period?.text("start"),
      reminderTo = period?.text("end"),
      reminderId = reminder?.text("id"),
    )
  }

  private fun JsonObject.coding(): JsonObject? =
    this["vaccineCode"]
      ?.jsonArray
      ?.firstOrNull()
      ?.jsonObject
      ?.get("coding")
      ?.jsonArray
      ?.firstOrNull()
      ?.jsonObject

  private fun JsonObject.code(): String? = coding()?.text("code")

  private fun JsonObject.complete(): Boolean =
    this["forecastStatus"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().any {
      it.jsonObject.text("code") == "complete"
    }

  private fun JsonObject.completed(): JsonObject =
    JsonObject(
      this +
        ("forecastStatus" to
          buildJsonObject {
            putJsonArray("coding") {
              add(
                buildJsonObject {
                  put("system", VisitBooking.FORECAST_STATUS)
                  put("code", "complete")
                  put("display", "Complete")
                }
              )
            }
          })
    )

  private suspend fun patientName(patientId: String): String {
    val name =
      repository
        .get("Patient", patientId)
        ?.let { json.encodeToJsonElement(Resource.serializer(), it).jsonObject["name"] }
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
    return name?.text("text")
      ?: name?.let {
        personName(it["given"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content, it.text("family"))
      }
      ?: patientId
  }

  private suspend fun records(type: String): List<JsonObject> =
    repository.all(type).map { json.encodeToJsonElement(Resource.serializer(), it).jsonObject }
}
