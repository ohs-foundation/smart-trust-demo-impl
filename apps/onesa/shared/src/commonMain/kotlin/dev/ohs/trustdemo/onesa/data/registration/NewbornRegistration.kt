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
package dev.ohs.trustdemo.onesa.data.registration

import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.trustdemo.hcert.CertificateFingerprint
import dev.ohs.trustdemo.hcert.DvcFhirMapper
import dev.ohs.trustdemo.hcert.DvcMappingOptions
import dev.ohs.trustdemo.hcert.DvcMin
import dev.ohs.trustdemo.onesa.data.verification.ScanTransactions
import dev.ohs.trustdemo.onesa.generateId
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.onesa.util.createEntry
import dev.ohs.trustdemo.onesa.util.profile
import dev.ohs.trustdemo.onesa.util.putEntry
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A card the CHW scanned: its HC1 text and what it says, read without any trust check. */
data class ScannedCard(val hc1: String, val dvc: DvcMin)

/** The linkIds of the household registration form. */
object RegistrationItems {
  const val HOUSEHOLD = "household"
  const val GIVEN = "given"
  const val FAMILY = "family"
  const val SEX = "sex"
  const val BIRTH_DATE = "birthDate"
  const val BIRTH_COUNTRY = "birthCountry"
  const val FOREIGN_CARDS = "foreignCards"
  const val RELATIONSHIP = "relationship"
  const val MOTHER = "mother"
  const val FATHER = "father"
}

/**
 * Everything the registration transaction is built from. [mother] and [father] are the household
 * members the form names as the child's parents; at least one is named. [newId] gives the id of the
 * record made for a purpose; the same answer for the same purpose lets a retry rewrite the records
 * of the attempt before it.
 */
data class RegistrationRequest(
  val response: QuestionnaireResponse,
  val household: Group,
  val mother: Patient?,
  val father: Patient?,
  val cards: List<ScannedCard>,
  val practitionerId: String,
  val organizationId: String,
  val now: Instant,
  val newId: (purpose: String) -> String = { generateId() },
)

/**
 * Turns the answered household registration form and the scanned cards into the one transaction
 * that registers a newborn: the child, the parents named as related persons, the household's new
 * member and managing entity, the parents' links, the reported doses and the referral.
 *
 * Extraction is in code rather than the data-capture template engine: the transaction updates an
 * existing Group and Patients, takes doses from scanned cards, and wires references between records
 * created together, none of which a Questionnaire template expresses.
 */
object NewbornRegistration {
  const val QUESTIONNAIRE_URL =
    "http://ohs.dev/fhir/trust-demo/Questionnaire/TrustDemoHouseholdRegistration"

  /** The system of the codes that name a household member as an answer to the form. */
  const val MEMBER_SYSTEM = "http://ohs.dev/fhir/trust-demo/CodeSystem/household-member"

  private const val ROLE_CODE = "http://terminology.hl7.org/CodeSystem/v3-RoleCode"
  private const val GENDER = "http://hl7.org/fhir/administrative-gender"
  private const val SNOMED = "http://snomed.info/sct"
  private const val REFERRAL_REASON =
    "Unverified foreign vaccination cards presented on a home visit (primarySource false)"
  private const val REFERRAL_TEXT =
    "Verify foreign paper DVCs and map the birth doses to the national schedule"
  private const val CARD_NOTE =
    "Read from a paper DVC the family showed, without verifying its signature: reported, not verified."

  private const val PRODUCT_SYSTEM =
    "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVaccineProduct"

  /** The demonstrator's products, as the IG's TrustDemoVaccineProduct names them. */
  val PRODUCTS =
    mapOf(
      "vp-hepb-peds-monovalent" to "Hepatitis B vaccine, paediatric, monovalent",
      "vp-bcg-freeze-dried" to "BCG vaccine, freeze-dried",
    )

  private val json = FhirJson.instance

  /**
   * The answers the form opens with: the household, the parents when [members] hold exactly one
   * mother and one father on record, and the child as the first card names it.
   */
  fun initialAnswers(
    household: Group,
    cards: List<ScannedCard>,
    members: List<Patient> = emptyList(),
  ): Map<String, JsonObject> = buildMap {
    household.identifier
      .firstNotNullOfOrNull { it.value?.value }
      ?.let { put(RegistrationItems.HOUSEHOLD, buildJsonObject { put("valueString", it) }) }
    val adults = members.filter { it.link.isNotEmpty() }
    adults
      .singleOrNull { it.genderCode() == "female" }
      ?.let { put(RegistrationItems.MOTHER, member(it)) }
    adults
      .singleOrNull { it.genderCode() == "male" }
      ?.let { put(RegistrationItems.FATHER, member(it)) }
    val dvc = cards.firstOrNull()?.dvc ?: return@buildMap
    val name = DvcFhirMapper.humanName(dvc.n)
    name.given
      .firstNotNullOfOrNull { it.value }
      ?.let { put(RegistrationItems.GIVEN, buildJsonObject { put("valueString", it) }) }
    name.family?.value?.let {
      put(RegistrationItems.FAMILY, buildJsonObject { put("valueString", it) })
    }
    put(RegistrationItems.BIRTH_DATE, buildJsonObject { put("valueDate", dvc.dob) })
    put(RegistrationItems.SEX, coding(GENDER, dvc.s.lowercase()))
    put(RegistrationItems.FOREIGN_CARDS, buildJsonObject { put("valueBoolean", true) })
  }

  /**
   * The household members the CHW can name as the child's parents, as the form's answer options.
   */
  fun parentOptions(members: List<Patient>): List<JsonObject> = members.map(::member)

  /** The ids of the mother and the father the form names; either may be absent. */
  fun parentIds(response: QuestionnaireResponse): Pair<String?, String?> {
    val answers = response.toJson().answers()
    return answers[RegistrationItems.MOTHER] to answers[RegistrationItems.FATHER]
  }

  /** What S3 tells the CHW: the child, each dose as reported, and the referral's recipient. */
  fun summary(
    response: QuestionnaireResponse,
    cards: List<ScannedCard>,
    referredTo: String?,
  ): RegistrationSummary {
    val answers = response.toJson().answers()
    return RegistrationSummary(
      childName =
        "${answers.required(RegistrationItems.GIVEN)} ${answers.required(RegistrationItems.FAMILY)}",
      birthDate = answers.required(RegistrationItems.BIRTH_DATE),
      doses = cards.map { "${PRODUCTS[it.dvc.vx.vp] ?: it.dvc.vx.vp}, ${it.dvc.vx.dv}" },
      referredTo = referredTo.takeIf { cards.isNotEmpty() },
    )
  }

  /** The ids a registration under [key] writes: one confirmed registration, the same records. */
  fun idsFor(key: String): (String) -> String = { purpose ->
    ScanTransactions.idFrom(CertificateFingerprint.of("registration:$key:$purpose"))
  }

  fun transaction(request: RegistrationRequest): JsonObject {
    val answers = request.response.toJson().answers()
    val authored = request.now.toString()
    val today = authored.substringBefore('T')
    val org = "Organization/${request.organizationId}"
    val practitioner = "Practitioner/${request.practitionerId}"

    val childId = request.newId("child")
    val child = "Patient/$childId"
    val mother = request.mother?.let { Parent(it, "MTH", request.newId("related:${it.id}")) }
    val father = request.father?.let { Parent(it, "FTH", request.newId("related:${it.id}")) }
    val parents = listOfNotNull(mother, father)
    val informant =
      if (mother != null && father != null) {
        when (val code = answers.required(RegistrationItems.RELATIONSHIP)) {
          "MTH" -> mother
          "FTH" -> father
          else -> error("Unknown relationship $code")
        }
      } else {
        parents.single()
      }
    val doses =
      request.cards.map { it to request.newId("dose:${CertificateFingerprint.of(it.hc1)}") }

    val somsak = buildJsonObject {
      put("resourceType", "Patient")
      putProfile("TrustDemoOnesaPatient")
      put("active", true)
      putJsonArray("name") {
        add(
          buildJsonObject {
            put("use", "official")
            put("family", answers.required(RegistrationItems.FAMILY))
            putJsonArray("given") { add(JsonPrimitive(answers.required(RegistrationItems.GIVEN))) }
          }
        )
      }
      put("gender", answers.required(RegistrationItems.SEX))
      put("birthDate", answers.required(RegistrationItems.BIRTH_DATE))
      putJsonObject("managingOrganization") { put("reference", org) }
      putJsonArray("contact") {
        parents.forEach { parent ->
          add(
            buildJsonObject {
              putJsonArray("relationship") { add(role(parent)) }
              put("name", parent.name)
            }
          )
        }
      }
    }

    val relatedPersons =
      parents.map { parent ->
        parent to
          buildJsonObject {
            put("resourceType", "RelatedPerson")
            putJsonObject("patient") { put("reference", child) }
            putJsonArray("relationship") { add(role(parent)) }
            putJsonArray("name") { add(parent.name) }
            parent.gender?.let { put("gender", it) }
          }
      }

    val updatedParents =
      parents.map { parent ->
        val resource = json.encodeToJsonElement(Patient.serializer(), parent.patient).jsonObject
        val link = buildJsonObject {
          putJsonObject("other") { put("reference", parent.relatedPerson) }
          put("type", "seealso")
        }
        val links = resource["link"]?.jsonArray.orEmpty()
        parent to JsonObject(resource + ("link" to JsonArray(links.filter { it != link } + link)))
      }

    val group = json.encodeToJsonElement(Group.serializer(), request.household).jsonObject
    val members =
      group["member"]?.jsonArray.orEmpty().filter {
        it.jsonObject["entity"]?.jsonObject?.get("reference")?.jsonPrimitive?.contentOrNull != child
      } +
        buildJsonObject {
          putJsonObject("entity") { put("reference", child) }
          putJsonObject("period") { put("start", today) }
        }
    val updatedGroup =
      JsonObject(
        group +
          mapOf(
            "member" to JsonArray(members),
            "quantity" to JsonPrimitive(members.size),
            "managingEntity" to buildJsonObject { put("reference", informant.relatedPerson) },
          )
      )

    val immunizations =
      doses.map { (card, id) ->
        id to
          reported(card, child, request.now.epochSeconds).let { dose ->
            JsonObject(
              dose - "id" +
                ("patient" to buildJsonObject { put("reference", child) }) +
                ("meta" to profile("TrustDemoOnesaReportedImmunization")) +
                (product(card.dvc.vx.vp)?.let { mapOf("vaccineCode" to it) }.orEmpty()) +
                ("note" to buildJsonArray { add(buildJsonObject { put("text", CARD_NOTE) }) })
            )
          }
      }

    val referral = buildJsonObject {
      put("resourceType", "ServiceRequest")
      putProfile("TrustDemoOnesaReferral")
      put("status", "active")
      put("intent", "order")
      put("priority", "routine")
      putJsonObject("code") {
        putJsonArray("coding") {
          add(
            buildJsonObject {
              put("system", SNOMED)
              put("code", "3457005")
              put("display", "Patient referral")
            }
          )
        }
        put("text", REFERRAL_TEXT)
      }
      putJsonObject("subject") { put("reference", child) }
      put("authoredOn", authored)
      putJsonObject("requester") { put("reference", practitioner) }
      putJsonArray("performer") { add(buildJsonObject { put("reference", org) }) }
      putJsonArray("reasonCode") { add(buildJsonObject { put("text", REFERRAL_REASON) }) }
      putJsonArray("supportingInfo") {
        doses.forEach { (_, id) -> add(buildJsonObject { put("reference", "Immunization/$id") }) }
      }
    }

    val record =
      JsonObject(
        request.response.toJson() - "id" +
          ("meta" to profile("TrustDemoOnesaRegistration")) +
          ("questionnaire" to JsonPrimitive(QUESTIONNAIRE_URL)) +
          ("status" to JsonPrimitive("completed")) +
          ("subject" to buildJsonObject { put("reference", child) }) +
          ("authored" to JsonPrimitive(authored)) +
          ("author" to buildJsonObject { put("reference", practitioner) }) +
          ("source" to buildJsonObject { put("reference", informant.relatedPerson) })
      )

    return buildJsonObject {
      put("resourceType", "Bundle")
      put("type", "transaction")
      putJsonArray("entry") {
        add(createEntry("Patient", childId, somsak))
        relatedPersons.forEach { (parent, resource) ->
          add(createEntry("RelatedPerson", parent.relatedPersonId, resource))
        }
        updatedParents.forEach { (parent, resource) ->
          add(putEntry("Patient/${parent.patient.id}", resource, ifMatch = resource.ifMatch()))
        }
        add(putEntry("Group/${request.household.id}", updatedGroup, ifMatch = group.ifMatch()))
        immunizations.forEach { (id, resource) -> add(createEntry("Immunization", id, resource)) }
        if (immunizations.isNotEmpty())
          add(createEntry("ServiceRequest", request.newId("referral"), referral))
        add(createEntry("QuestionnaireResponse", request.newId("response"), record))
      }
    }
  }

  private class Parent(val patient: Patient, val code: String, val relatedPersonId: String) {
    val relatedPerson = "RelatedPerson/$relatedPersonId"
    val gender: String? = patient.genderCode()
    val name: JsonObject =
      patient.name.firstOrNull()?.let {
        buildJsonObject {
          it.family?.value?.let { family -> put("family", family) }
          putJsonArray("given") {
            it.given
              .mapNotNull { given -> given.value }
              .forEach { given -> add(JsonPrimitive(given)) }
          }
        }
      } ?: JsonObject(emptyMap())
  }

  private fun reported(card: ScannedCard, patientId: String, nowEpochSeconds: Long): JsonObject {
    val resources =
      DvcFhirMapper.map(
        card.dvc,
        DvcMappingOptions(
          patientId = patientId,
          certificateFingerprint = CertificateFingerprint.of(card.hc1),
          recordedAtEpochSeconds = nowEpochSeconds,
        ),
      )
    return json.encodeToJsonElement(Immunization.serializer(), resources.immunization).jsonObject
  }

  /**
   * The version the record was read at, as the `If-Match` that refuses a write over a newer one.
   */
  private fun JsonObject.ifMatch(): String? =
    this["meta"]?.jsonObject?.get("versionId")?.jsonPrimitive?.contentOrNull?.let { "W/\"$it\"" }

  private fun Patient.genderCode(): String? =
    json
      .encodeToJsonElement(Patient.serializer(), this)
      .jsonObject["gender"]
      ?.jsonPrimitive
      ?.contentOrNull

  private fun member(patient: Patient): JsonObject = buildJsonObject {
    putJsonObject("valueCoding") {
      put("system", MEMBER_SYSTEM)
      put("code", patient.id ?: error("A household member has no id"))
      put("display", displayName(patient))
    }
  }

  private fun displayName(patient: Patient): String =
    patient.name
      .firstOrNull()
      ?.let { name ->
        (name.given.mapNotNull { it.value } + listOfNotNull(name.family?.value)).joinToString(" ")
      }
      ?.takeIf { it.isNotBlank() } ?: patient.id.orEmpty()

  private fun product(vp: String): JsonObject? =
    PRODUCTS[vp]?.let { display ->
      buildJsonObject {
        putJsonArray("coding") {
          add(
            buildJsonObject {
              put("system", PRODUCT_SYSTEM)
              put("code", vp)
              put("display", display)
            }
          )
        }
      }
    }

  private fun role(parent: Parent): JsonObject = buildJsonObject {
    putJsonArray("coding") {
      add(
        buildJsonObject {
          put("system", ROLE_CODE)
          put("code", parent.code)
          put("display", if (parent.code == "MTH") "mother" else "father")
        }
      )
    }
  }

  private fun JsonObjectBuilder.putProfile(name: String) {
    put("meta", profile(name))
  }

  private fun coding(system: String, code: String): JsonObject = buildJsonObject {
    putJsonObject("valueCoding") {
      put("system", system)
      put("code", code)
    }
  }

  private fun Map<String, String>.required(linkId: String): String =
    this[linkId] ?: error("The registration form has no answer for $linkId")

  private fun QuestionnaireResponse.toJson(): JsonObject =
    json.encodeToJsonElement(QuestionnaireResponse.serializer(), this).jsonObject

  /** The first answer to each item, as plain text: the string, date, boolean or coding's code. */
  private fun JsonObject.answers(): Map<String, String> =
    getValue("item")
      .jsonArray
      .mapNotNull { item ->
        val answer = item.jsonObject["answer"]?.jsonArray?.firstOrNull()?.jsonObject
        val text =
          answer?.let {
            (it["valueString"] ?: it["valueDate"] ?: it["valueBoolean"])
              ?.jsonPrimitive
              ?.contentOrNull
              ?: it["valueCoding"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
          }
        text?.let { item.jsonObject.getValue("linkId").jsonPrimitive.content to it }
      }
      .toMap()
}
