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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

/** Reads booked visits from the synced copy: a child's, for the profile, and the clinic's. */
class BookedVisits(private val repository: FhirRepository) {
  private val json = FhirJson.instance

  suspend fun of(patientId: String): BookedVisit? =
    records("ImmunizationRecommendation")
      .firstOrNull { it["patient"]?.jsonObject?.text("reference") == "Patient/$patientId" }
      ?.let { visit(it) }

  /** Every visit with a reminder still requested, soonest first. */
  suspend fun all(): List<ClinicVisit> =
    records("ImmunizationRecommendation")
      .mapNotNull { recommendation ->
        val patientId =
          recommendation["patient"]?.jsonObject?.text("reference")?.removePrefix("Patient/")
            ?: return@mapNotNull null
        val visit = visit(recommendation).takeIf { it.reminderId != null } ?: return@mapNotNull null
        ClinicVisit(patientId, patientName(patientId), visit)
      }
      .sortedBy { it.visit.reminderFrom }

  private suspend fun visit(recommendation: JsonObject): BookedVisit {
    val reminder =
      records("Task").firstOrNull {
        it["focus"]?.jsonObject?.text("reference") ==
          "ImmunizationRecommendation/${recommendation.text("id")}" &&
          it.text("status") == "requested"
      }
    val period = reminder?.get("restriction")?.jsonObject?.get("period")?.jsonObject
    return BookedVisit(
      doses =
        recommendation["recommendation"]?.jsonArray.orEmpty().map { entry ->
          val coding =
            entry.jsonObject["vaccineCode"]
              ?.jsonArray
              ?.firstOrNull()
              ?.jsonObject
              ?.get("coding")
              ?.jsonArray
              ?.firstOrNull()
              ?.jsonObject
          val due = entry.jsonObject["dateCriterion"]?.jsonArray?.firstOrNull()?.jsonObject
          RecommendedDose(
            name = coding?.text("display") ?: coding?.text("code").orEmpty(),
            due = due?.text("value"),
            code = coding?.text("code"),
          )
        },
      reminderFrom = period?.text("start"),
      reminderTo = period?.text("end"),
      reminderId = reminder?.text("id"),
    )
  }

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
