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
package dev.ohs.trustdemo.onesa.data.repository

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.util.FhirJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the clinic's screens say about a child beside their doses: the household they belong to
 * ([household], [householdId]), how their latest referral stands ([referralStatus]), how many card
 * scans were audited for them ([scanAudits]) and which Provenance records removed reported doses
 * ([removals], the count of doses each removed).
 */
data class PatientContext(
  val patientId: String,
  val name: String,
  val gender: String? = null,
  val birthDate: String? = null,
  val temporaryId: String? = null,
  val groupId: String? = null,
  val household: String? = null,
  val householdId: String? = null,
  val referralStatus: String? = null,
  val scanAudits: Int = 0,
  val removals: Map<String, Int> = emptyMap(),
)

/** Reads a [PatientContext] from the synced copy. */
class PatientContexts(private val repository: FhirRepository) {
  private val json = FhirJson.instance

  suspend fun of(patientId: String): PatientContext? {
    val patient = repository.get("Patient", patientId)?.toJson() ?: return null
    val reference = "Patient/$patientId"
    val name = patient["name"]?.jsonArray?.firstOrNull()?.jsonObject
    val group =
      records("Group").firstOrNull { group ->
        group["member"]?.jsonArray.orEmpty().any {
          it.jsonObject["entity"]?.jsonObject?.text("reference") == reference
        }
      }
    val referral =
      records("ServiceRequest")
        .filter { it["subject"]?.jsonObject?.text("reference") == reference }
        .maxByOrNull { it.text("authoredOn").orEmpty() }
    val doses =
      records("Immunization")
        .filter { it["patient"]?.jsonObject?.text("reference") == reference }
        .mapNotNull { it.text("id")?.let { id -> "Immunization/$id" } }
        .toSet()
    val removals =
      records("Provenance")
        .filter { provenance ->
          provenance.references("target").any { it == reference || it in doses }
        }
        .mapNotNull { provenance ->
          val removed =
            provenance["entity"]?.jsonArray.orEmpty().count {
              it.jsonObject.text("role") == "removal"
            }
          provenance.text("id")?.takeIf { removed > 0 }?.let { it to removed }
        }
        .toMap()
    return PatientContext(
      patientId = patientId,
      name =
        name?.text("text")
          ?: personName(
            name?.get("given")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            name?.text("family"),
          ),
      gender = patient.text("gender"),
      birthDate = patient.text("birthDate"),
      temporaryId =
        patient["identifier"]
          ?.jsonArray
          .orEmpty()
          .map { it.jsonObject }
          .firstOrNull { it.text("system") == TEMPORARY_ID }
          ?.text("value"),
      groupId = group?.text("id"),
      household = group?.text("name"),
      householdId = group?.get("identifier")?.jsonArray?.firstOrNull()?.jsonObject?.text("value"),
      referralStatus = referral?.text("status"),
      scanAudits =
        records("AuditEvent").count { audit ->
          audit["entity"]?.jsonArray.orEmpty().any {
            it.jsonObject["what"]?.jsonObject?.text("reference") == reference
          } &&
            audit["subtype"]?.jsonArray.orEmpty().any {
              it.jsonObject.text("system") == VERIFICATION_RESULT
            }
        },
      removals = removals,
    )
  }

  private suspend fun records(type: String): List<JsonObject> =
    repository.all(type).map { it.toJson() }

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject

  private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.references(name: String): List<String> =
    this[name]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.text("reference") }

  private companion object {
    const val TEMPORARY_ID = "http://smart.who.int/trust-demo/sid/onesa-temporary-id"
    const val VERIFICATION_RESULT =
      "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVerificationResult"
  }
}
