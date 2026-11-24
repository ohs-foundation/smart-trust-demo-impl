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
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.control.Dates
import dev.ohs.trustdemo.control.Step
import dev.ohs.trustdemo.control.expectations
import dev.ohs.trustdemo.hcert.DvcMin
import dev.ohs.trustdemo.hcert.DvcVaccineDetails
import dev.ohs.trustdemo.onesa.util.FhirJson
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NewbornRegistrationTest {
  private val now = Instant.parse("2026-11-14T03:00:00Z")
  private val resetDay = LocalDate.parse("2026-11-14")
  private val dates = Dates(resetDay)
  private val organization = "TrustDemoLomaruDhc"

  private val household =
    decode<Group>(
      """{"resourceType":"Group","id":"TrustDemoHousehold","type":"person","actual":true,
        "identifier":[{"system":"http://smart.who.int/trust-demo/sid/onesa-household-id","value":"HH-XXU-204"}],
        "name":"Sengmany household","quantity":2,
        "member":[{"entity":{"reference":"Patient/TrustDemoBounmyPatient"}},
                  {"entity":{"reference":"Patient/TrustDemoKhamlaPatient"}}]}"""
    )

  private val parents =
    listOf(
      decode<Patient>(
        """{"resourceType":"Patient","id":"TrustDemoBounmyPatient","gender":"male",
          "managingOrganization":{"reference":"Organization/$organization"},
          "name":[{"family":"SENGMANY","given":["Bounmy"]}]}"""
      ),
      decode<Patient>(
        """{"resourceType":"Patient","id":"TrustDemoKhamlaPatient","gender":"female",
          "managingOrganization":{"reference":"Organization/$organization"},
          "name":[{"family":"SENGMANY","given":["Khamla"]}]}"""
      ),
    )

  private val cards =
    listOf("a3K3X000006MiiXUAS" to "HC1:HEPB", "a3K3X000006MiisUAC" to "HC1:BCG").map {
      (product, hc1) ->
      ScannedCard(
        hc1,
        DvcMin(
          n = "Somsak SENGMANY",
          dob = dates.birthDate.toString(),
          s = "male",
          nt = "XXR",
          vx =
            DvcVaccineDetails(
              vp = product,
              dv = dates.birthDate.toString(),
              issuerId = "KRH",
              bo = "XXR-4821",
            ),
          v = "1.0.0",
        ),
      )
    }

  private val response =
    decode<QuestionnaireResponse>(
      """{"resourceType":"QuestionnaireResponse","status":"completed","item":[
        {"linkId":"household","answer":[{"valueString":"HH-XXU-204"}]},
        {"linkId":"given","answer":[{"valueString":"Somsak"}]},
        {"linkId":"family","answer":[{"valueString":"Sengmany"}]},
        {"linkId":"sex","answer":[{"valueCoding":{"system":"http://hl7.org/fhir/administrative-gender","code":"male"}}]},
        {"linkId":"birthDate","answer":[{"valueDate":"${dates.birthDate}"}]},
        {"linkId":"birthCountry","answer":[{"valueString":"Taifafeki"}]},
        {"linkId":"foreignCards","answer":[{"valueBoolean":true}]},
        {"linkId":"relationship","answer":[{"valueCoding":{"system":"http://terminology.hl7.org/CodeSystem/v3-RoleCode","code":"FTH"}}]}]}"""
    )

  private val ids = generateSequence(1) { it + 1 }.map { "new-$it" }.iterator()

  private val transaction: JsonObject =
    NewbornRegistration.transaction(
      RegistrationRequest(
        response = response,
        household = household,
        mother = parents[1],
        father = parents[0],
        cards = cards,
        practitionerId = "TrustDemoVilay",
        organizationId = organization,
        now = now,
        newId = { ids.next() },
      )
    )

  private val entries = transaction.getValue("entry").jsonArray.map { it.jsonObject }

  private val resourcesByType: Map<String, List<Resource>> =
    entries.groupBy({ it.resource().type() }) {
      FhirJson.instance.decodeFromJsonElement(Resource.serializer(), it.getValue("resource"))
    }

  @Test
  fun theReviewReadsBackWhatTheTransactionWrites() {
    val review = NewbornRegistration.review(transaction)

    assertEquals(
      listOf("Somsak", "Sengmany", "male"),
      listOf(review.given, review.family, review.sex),
    )
    assertEquals(dates.birthDate.toString(), review.birthDate)
    assertEquals("Taifafeki", review.birthCountry)
    assertEquals(false, review.hasNationalId)
    assertEquals(
      setOf(ReviewedParent("Khamla Sengmany", "MTH"), ReviewedParent("Bounmy Sengmany", "FTH")),
      review.parents.toSet(),
    )
    assertEquals(setOf("a3K3X000006MiiXUAS", "a3K3X000006MiisUAC"), review.doses.toSet())
    assertEquals("Organization/$organization", review.referral?.performer)
    assertEquals("routine", review.referral?.priority)
    assertEquals(entries.groupingBy { it.resource().type() }.eachCount(), review.writes)
  }

  @Test
  fun anUrgentReferralIsWrittenUrgentAndTheDefaultIsRoutine() {
    fun priority(transaction: JsonObject) =
      transaction
        .getValue("entry")
        .jsonArray
        .map { it.jsonObject.resource() }
        .single { it.type() == "ServiceRequest" }
        .getValue("priority")
        .jsonPrimitive
        .content
    val request =
      RegistrationRequest(
        response = response,
        household = household,
        mother = parents[1],
        father = parents[0],
        cards = cards,
        practitionerId = "TrustDemoVilay",
        organizationId = organization,
        now = now,
      )

    assertEquals("routine", priority(NewbornRegistration.transaction(request)))
    val urgent = NewbornRegistration.transaction(request.copy(urgent = true))
    assertEquals("urgent", priority(urgent))
    assertEquals("urgent", NewbornRegistration.review(urgent).referral?.priority)
  }

  @Test
  fun registrationExtractsTheJ2Records() {
    for (expectation in expectations.getValue(Step.J2)) {
      val found =
        resourcesByType.getValue(expectation.query.type).filter { found ->
          expectation.query.params["questionnaire"]?.let { canonical ->
            FhirJson.instance
              .encodeToJsonElement(Resource.serializer(), found)
              .jsonObject["questionnaire"]
              ?.jsonPrimitive
              ?.content == canonical
          } ?: true
        }
      assertTrue(expectation.satisfied(found, dates), expectation.label)
    }
  }

  private val existing = setOf("TrustDemoHousehold") + parents.mapNotNull { it.id }

  private fun JsonObject.isNew(): Boolean =
    resource().getValue("id").jsonPrimitive.content !in existing

  @Test
  fun theTransactionPassesTheGroupWriteRule() {
    val newPatients =
      entries
        .filter { it.isNew() && it.resource().type() == "Patient" }
        .onEach {
          assertEquals(
            "Organization/$organization",
            it
              .resource()["managingOrganization"]
              ?.jsonObject
              ?.get("reference")
              ?.jsonPrimitive
              ?.content,
          )
        }
        .map { "Patient/${it.resource().getValue("id").jsonPrimitive.content}" }
    assertEquals(1, newPatients.size)

    val inScope = parents.map { "Patient/${it.id}" }.toSet() + newPatients
    val group = entries.single { it.resource().type() == "Group" }
    val members =
      group.resource().getValue("member").jsonArray.map {
        it.jsonObject.getValue("entity").jsonObject.getValue("reference").jsonPrimitive.content
      }
    assertEquals(3, members.size)
    assertTrue(inScope.containsAll(members), "every member is in scope: $members")
    assertEquals(3, group.resource().getValue("quantity").jsonPrimitive.content.toInt())
    assertNotNull(group.resource()["managingEntity"])
  }

  @Test
  fun everyNewRecordPointsAtTheNewPatientUnderAGeneratedId() {
    val somsak = entries.single { it.isNew() && it.resource().type() == "Patient" }
    val reference = "Patient/${somsak.resource().getValue("id").jsonPrimitive.content}"
    val dependants =
      setOf("RelatedPerson", "Immunization", "ServiceRequest", "QuestionnaireResponse")
    val subjects =
      entries
        .filter { it.resource().type() in dependants }
        .map { entry ->
          listOf("patient", "subject").firstNotNullOf { key ->
            entry.resource()[key]?.jsonObject?.get("reference")?.jsonPrimitive?.content
          }
        }
    assertEquals(6, subjects.size)
    assertTrue(subjects.all { it == reference }, "$subjects")
    assertTrue(
      entries
        .filter { it.isNew() }
        .none { it.resource().getValue("id").jsonPrimitive.content.startsWith("TrustDemo") }
    )
    val parentLinks =
      entries
        .filter { !it.isNew() && it.resource().type() == "Patient" }
        .map { it.resource().getValue("link").jsonArray.first().jsonObject }
    assertEquals(2, parentLinks.size)
    assertTrue(parentLinks.all { it.getValue("type").jsonPrimitive.content == "seealso" })
    val doses = entries.filter { it.resource().type() == "Immunization" }.map { it.resource() }
    assertTrue(doses.all { it.getValue("primarySource").jsonPrimitive.content == "false" })
    assertTrue(
      doses.all {
        it
          .getValue("vaccineCode")
          .jsonObject
          .getValue("coding")
          .jsonArray
          .first()
          .jsonObject
          .getValue("system")
          .jsonPrimitive
          .content
          .endsWith("PreQualProductIds")
      }
    )
  }

  @Test
  fun theTransactionStampsWholeSeconds() {
    val stamped =
      NewbornRegistration.transaction(
        RegistrationRequest(
          response = response,
          household = household,
          mother = parents[1],
          father = parents[0],
          cards = cards,
          practitionerId = "TrustDemoVilay",
          organizationId = organization,
          now = Instant.parse("2026-11-14T13:28:59.510967Z"),
          newId = { ids.next() },
        )
      )

    val times = stamped.strings().filter { it.startsWith("2026-11-14T") }
    assertTrue(times.isNotEmpty())
    assertEquals(setOf("2026-11-14T13:28:59Z"), times.toSet())
  }

  private fun JsonElement.strings(): List<String> =
    when (this) {
      is JsonObject -> values.flatMap { it.strings() }
      is JsonArray -> flatMap { it.strings() }
      is JsonPrimitive -> if (isString) listOf(content) else emptyList()
    }

  private fun JsonObject.resource(): JsonObject = getValue("resource").jsonObject

  private fun JsonObject.type(): String = getValue("resourceType").jsonPrimitive.content

  private inline fun <reified T : Resource> decode(json: String): T =
    FhirJson.instance.decodeFromString(Resource.serializer(), json) as T
}
