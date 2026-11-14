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
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.repository.personName
import dev.ohs.trustdemo.onesa.util.FhirJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A referral to this clinic to verify a child's cards (J2 wrote it), as the inbox lists it. */
data class Referral(
  val id: String,
  val patientId: String,
  val patientName: String,
  val authoredOn: String? = null,
  val reportedDoses: List<String> = emptyList(),
)

/** A referral the signed-in CHW sent, as her referrals tab lists it. */
data class SentReferral(
  val id: String,
  val patientName: String,
  val status: String?,
  val performer: String? = null,
  val authoredOn: String? = null,
  val reason: String? = null,
)

/** A referred child as the synced record names them. */
data class Child(val name: String, val birthDate: String?)

/** The referrals waiting at this clinic, read from the synced copy. */
class ReferralInbox(private val repository: FhirRepository) {
  private val json = FhirJson.instance

  fun observe(): Flow<List<Referral>> = repository.revision.map { waiting() }

  suspend fun get(id: String): Referral? = waiting().firstOrNull { it.id == id }

  /** The referrals [practitionerId] requested, open or done, newest first. */
  fun observeSent(practitionerId: String?): Flow<List<SentReferral>> =
    repository.revision.map { sent(practitionerId) }

  internal suspend fun sent(practitionerId: String?): List<SentReferral> =
    repository
      .all("ServiceRequest")
      .map { it.toJson() }
      .filter {
        it.isReferral() &&
          (practitionerId == null || it.reference("requester") == "Practitioner/$practitionerId")
      }
      .mapNotNull { request ->
        val id = request["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val patientId = request.reference("subject")?.removePrefix("Patient/")
        val performer =
          request["performer"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("reference")
            ?.jsonPrimitive
            ?.contentOrNull
        SentReferral(
          id = id,
          patientName = patientId?.let { patientName(it) } ?: id,
          status = request["status"]?.jsonPrimitive?.contentOrNull,
          performer = performer?.let { organizationName(it) },
          authoredOn = request["authoredOn"]?.jsonPrimitive?.contentOrNull,
          reason =
            request["reasonCode"]
              ?.jsonArray
              ?.firstOrNull()
              ?.jsonObject
              ?.get("text")
              ?.jsonPrimitive
              ?.contentOrNull,
        )
      }
      .sortedByDescending { it.authoredOn }

  private suspend fun organizationName(reference: String): String =
    repository
      .get("Organization", reference.removePrefix("Organization/"))
      ?.toJson()
      ?.get("name")
      ?.jsonPrimitive
      ?.contentOrNull ?: reference

  /** The doses recorded from the family's cards, not yet verified, for [patientId]. */
  suspend fun reportedDoses(patientId: String): List<ReportedDose> =
    repository
      .all("Immunization")
      .map { it.toJson() }
      .filter {
        it.reference("patient") == "Patient/$patientId" && it.flag("primarySource") == false
      }
      .mapNotNull { dose ->
        val id = dose["id"]?.jsonPrimitive?.contentOrNull
        val product = dose.product()
        val date = dose["occurrenceDateTime"]?.jsonPrimitive?.contentOrNull?.substringBefore('T')
        if (id != null && product != null) ReportedDose(id, product, date) else null
      }

  private suspend fun waiting(): List<Referral> =
    repository
      .all("ServiceRequest")
      .map { it.toJson() }
      .filter { it["status"]?.jsonPrimitive?.contentOrNull == "active" && it.isReferral() }
      .mapNotNull { request ->
        val id = request["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val patientId =
          request.reference("subject")?.removePrefix("Patient/") ?: return@mapNotNull null
        Referral(
          id = id,
          patientId = patientId,
          patientName = patientName(patientId),
          authoredOn = request["authoredOn"]?.jsonPrimitive?.contentOrNull,
          reportedDoses = reportedDoses(patientId).map { it.product },
        )
      }
      .sortedByDescending { it.authoredOn }

  /** The child as the synced record has them: the name to show and the date of birth. */
  suspend fun child(patientId: String): Child {
    val patient = repository.get("Patient", patientId)?.toJson()
    return Child(patientName(patientId), patient?.get("birthDate")?.jsonPrimitive?.contentOrNull)
  }

  private suspend fun patientName(patientId: String): String =
    repository.get("Patient", patientId)?.toJson()?.get("name")?.jsonArray?.firstOrNull()?.let {
      val name = it.jsonObject
      name["text"]?.jsonPrimitive?.contentOrNull
        ?: personName(
          name["given"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
          name["family"]?.jsonPrimitive?.contentOrNull,
        )
    } ?: patientId

  private fun JsonObject.isReferral(): Boolean =
    this["code"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().any {
      it.jsonObject["code"]?.jsonPrimitive?.contentOrNull == REFERRAL_CODE
    }

  private fun JsonObject.product(): String? {
    val codings =
      this["vaccineCode"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().map { it.jsonObject }
    return (codings.firstOrNull {
        it["system"]?.jsonPrimitive?.contentOrNull == ScanTransactions.PRODUCT_SYSTEM
      } ?: codings.firstOrNull())
      ?.get("code")
      ?.jsonPrimitive
      ?.contentOrNull
  }

  private fun JsonObject.reference(name: String): String? =
    this[name]?.jsonObject?.get("reference")?.jsonPrimitive?.contentOrNull

  private fun JsonObject.flag(name: String): Boolean? =
    this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject

  private companion object {
    const val REFERRAL_CODE = "3457005"
  }
}
