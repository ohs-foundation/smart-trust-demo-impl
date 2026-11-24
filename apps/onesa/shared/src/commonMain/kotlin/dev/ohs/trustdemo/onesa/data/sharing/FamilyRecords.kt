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
package dev.ohs.trustdemo.onesa.data.sharing

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.hcert.CertificateFingerprint
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.verification.ScanTransactions
import dev.ohs.trustdemo.onesa.util.FhirJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A household member as the wallet lists them: their [relation] (`CHILD`, or a parent's v3
 * RoleCode) and whether they head the household.
 */
data class FamilyMember(
  val id: String,
  val name: String?,
  val given: String?,
  val birthDate: String?,
  val relation: String?,
  val head: Boolean,
)

/**
 * A dose on a member's record. [fromCard] when it came from a foreign card (the record carries the
 * card's fingerprint), and [primarySource] whether it counts as verified.
 */
data class FamilyDose(
  val id: String,
  val name: String,
  val scheduleCode: String?,
  val date: String?,
  val lot: String?,
  val origin: String?,
  val primarySource: Boolean,
  val fromCard: Boolean,
)

/** One of a member's health records as the wallet lists it: what it is, then its specifics. */
data class HealthItem(val label: String, val detail: String?)

/** A member's own problems, medicines, allergies and vital signs, as their summary shows them. */
data class FamilyHealth(
  val problems: List<HealthItem> = emptyList(),
  val medications: List<HealthItem> = emptyList(),
  val allergies: List<HealthItem> = emptyList(),
  val vitalSigns: List<HealthItem> = emptyList(),
)

/**
 * Reads the wallet's household and doses from what the family phone syncs: Groups, Patients and
 * Immunizations. It holds no RelatedPerson, so a parent's role comes from the child's own record,
 * whose contacts name each parent and their relationship; the head is the member the Group's
 * managing entity is linked from.
 */
class FamilyRecords(private val repository: FhirRepository) {
  private val json = FhirJson.instance

  suspend fun members(people: List<Person>): List<FamilyMember> {
    val patients = people.associate { it.id to repository.get("Patient", it.id)?.toJson() }
    val ids = people.map { "Patient/${it.id}" }.toSet()
    val head =
      repository
        .all("Group")
        .map { it.toJson() }
        .firstOrNull { group -> group.memberRefs().any { it in ids } }
        ?.get("managingEntity")
        ?.jsonObject
        ?.text("reference")
    val parents =
      patients.values.filterNotNull().flatMap { child ->
        child["contact"]?.jsonArray.orEmpty().mapNotNull { contact ->
          val code = contact.jsonObject.relationship() ?: return@mapNotNull null
          val name = contact.jsonObject["name"]?.jsonObject ?: return@mapNotNull null
          Triple(child.text("id"), name.key(), code)
        }
      }
    return people.map { person ->
      val patient = patients[person.id]
      val key = patient?.get("name")?.jsonArray?.firstOrNull()?.jsonObject?.key()
      val links =
        patient?.get("link")?.jsonArray.orEmpty().mapNotNull {
          it.jsonObject["other"]?.jsonObject?.text("reference")
        }
      FamilyMember(
        id = person.id,
        name = person.name,
        given =
          patient
            ?.get("name")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("given")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonPrimitive
            ?.contentOrNull,
        birthDate = patient?.text("birthDate"),
        relation =
          when {
            parents.any { it.first == person.id } -> CHILD
            else -> parents.firstOrNull { it.first != person.id && it.second == key }?.third
          },
        head = head != null && (head == "Patient/${person.id}" || head in links),
      )
    }
  }

  /** [patientId]'s doses, oldest first. */
  suspend fun doses(patientId: String): List<FamilyDose> =
    repository
      .all("Immunization")
      .map { it.toJson() }
      .filter { it["patient"]?.jsonObject?.text("reference") == "Patient/$patientId" }
      .mapNotNull { dose ->
        val codings =
          dose["vaccineCode"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().map { it.jsonObject }
        val schedule = codings.firstOrNull { it.text("system") == ScanTransactions.EPI_SYSTEM }
        FamilyDose(
          id = dose.text("id") ?: return@mapNotNull null,
          name =
            schedule?.text("display")
              ?: dose["vaccineCode"]?.jsonObject?.text("text")
              ?: codings.firstNotNullOfOrNull { it.text("display") ?: it.text("code") }
              ?: return@mapNotNull null,
          scheduleCode = schedule?.text("code"),
          date = dose.text("occurrenceDateTime")?.substringBefore('T'),
          lot = dose.text("lotNumber"),
          origin = dose["location"]?.jsonObject?.text("display"),
          primarySource = dose.text("primarySource") == "true",
          fromCard =
            dose["identifier"]?.jsonArray.orEmpty().any {
              it.jsonObject.text("system") == CertificateFingerprint.SYSTEM
            },
        )
      }
      .sortedBy { it.date }

  /** [patientId]'s own problems, medicines, allergies and vital signs. */
  suspend fun health(patientId: String): FamilyHealth {
    suspend fun own(type: String, field: String) =
      repository
        .all(type)
        .map { it.toJson() }
        .filter { it[field]?.jsonObject?.text("reference") == "Patient/$patientId" }
    return FamilyHealth(
      problems =
        own("Condition", "subject").map {
          HealthItem(it.concept("code"), it.firstCoding("clinicalStatus")?.text("code"))
        },
      medications =
        own("MedicationStatement", "subject").map {
          HealthItem(
            it.concept("medicationCodeableConcept"),
            it["dosage"]?.jsonArray?.firstOrNull()?.jsonObject?.text("text"),
          )
        },
      allergies =
        own("AllergyIntolerance", "patient").map { allergy ->
          val reaction =
            allergy["reaction"]?.jsonArray?.firstOrNull()?.jsonObject?.get("manifestation")
          HealthItem(
            allergy.concept("code"),
            reaction?.jsonArray?.firstOrNull()?.jsonObject?.conceptText(),
          )
        },
      vitalSigns = own("Observation", "subject").map(::vitalSign),
    )
  }

  private fun vitalSign(observation: JsonObject): HealthItem {
    val quantities =
      observation["component"]?.jsonArray?.map { it.jsonObject["valueQuantity"]?.jsonObject }
        ?: listOf(observation["valueQuantity"]?.jsonObject)
    val value =
      quantities
        .mapNotNull { it?.text("value") }
        .joinToString("/")
        .ifEmpty { null }
        ?.let { listOfNotNull(it, quantities.firstOrNull()?.text("unit")).joinToString(" ") }
    return HealthItem(
      observation.concept("code"),
      listOfNotNull(value, observation.text("effectiveDateTime")?.substringBefore('T'))
        .joinToString(" • ")
        .ifEmpty { null },
    )
  }

  private fun JsonObject.concept(name: String): String =
    this[name]?.jsonObject?.conceptText().orEmpty()

  private fun JsonObject.conceptText(): String? =
    text("text")
      ?: this["coding"]?.jsonArray?.firstOrNull()?.jsonObject?.let {
        it.text("display") ?: it.text("code")
      }

  private fun JsonObject.firstCoding(name: String): JsonObject? =
    this[name]?.jsonObject?.get("coding")?.jsonArray?.firstOrNull()?.jsonObject

  private fun JsonObject.memberRefs(): List<String> =
    this["member"]?.jsonArray.orEmpty().mapNotNull {
      it.jsonObject["entity"]?.jsonObject?.text("reference")
    }

  private fun JsonObject.relationship(): String? =
    this["relationship"]
      ?.jsonArray
      .orEmpty()
      .flatMap { it.jsonObject["coding"]?.jsonArray.orEmpty() }
      .map { it.jsonObject }
      .firstOrNull { it.text("system") == ROLE_CODE }
      ?.text("code")

  /** A name as given and family name, case aside, to match a contact to a member. */
  private fun JsonObject.key(): String =
    listOfNotNull(
        this["given"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
        text("family"),
      )
      .joinToString(" ")
      .lowercase()

  private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject

  companion object {
    const val CHILD = "CHILD"
    private const val ROLE_CODE = "http://terminology.hl7.org/CodeSystem/v3-RoleCode"
  }
}
