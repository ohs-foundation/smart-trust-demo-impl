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
import dev.ohs.trustdemo.control.Dates
import dev.ohs.trustdemo.control.Step
import dev.ohs.trustdemo.control.expectations
import dev.ohs.trustdemo.hcert.CertificateFingerprint
import dev.ohs.trustdemo.hcert.FailureReason
import dev.ohs.trustdemo.hcert.InMemoryTrustListStorage
import dev.ohs.trustdemo.hcert.TrustListCache
import dev.ohs.trustdemo.hcert.VerifyStep
import dev.ohs.trustdemo.onesa.data.hcert.BundledTrustLists
import dev.ohs.trustdemo.onesa.data.hcert.HcertService
import dev.ohs.trustdemo.onesa.data.hcert.PinnedTrustList
import dev.ohs.trustdemo.onesa.data.hcert.SignedCards
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.trustkit.StandIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CardVerificationTest {
  private val nowSeconds = 1_800_000_000L
  private val patientId = "somsak-id"
  private val referral = Referral("referral-1", patientId, "Somsak Sengmany")
  private val gateway = FakeGateway()
  private val repository = InMemorySampleFhirRepository()

  private val service =
    VerificationService(
      repository = repository,
      hcert =
        HcertService(
          local =
            PinnedTrustList(
              name = "Local test list",
              signer = BundledTrustLists::localSigner,
              cache =
                TrustListCache(
                  InMemoryTrustListStorage(),
                  url = "http://trust.gdhcn.example/local-test-list.json",
                  bundled = { resource("/local-test-list.json") },
                ),
            ),
          gdhcnDev = BundledTrustLists.gdhcnDev(InMemoryTrustListStorage()),
        ),
      session = { RegistrationSession("token", "chanthason-id", "lomaru-dhc") },
      httpClient = HttpClient(gateway.engine),
      baseUrl = "http://gateway/fhir",
      refresh = {},
      clock = { Instant.fromEpochSeconds(nowSeconds) },
    )

  private val hepB = SignedCards.dvc(StandIn.XXR_CARD, nowSeconds, "vp-hepb-peds-monovalent")
  private val bcg = SignedCards.dvc(StandIn.XXR_CARD, nowSeconds, "vp-bcg-freeze-dried")

  private suspend fun withReportedDoses() {
    gateway.immunizations["reported-hepb"] =
      reported("reported-hepb", "vp-hepb-peds-monovalent", hepB)
    gateway.immunizations["reported-bcg"] = reported("reported-bcg", "vp-bcg-freeze-dried", bcg)
    gateway.immunizations.values.forEach { repository.upsert(decode(it)) }
  }

  @Test
  fun theSameCardScannedTwiceGivesOneVerifiedImmunization() = runTest {
    withReportedDoses()

    assertIs<ScanOutcome.Imported>(service.scan(referral, hepB))
    assertIs<ScanOutcome.AlreadyImported>(service.scan(referral, hepB))

    val verified = gateway.immunizations.values.filter { it.flag("primarySource") == true }
    assertEquals(1, verified.size)
    assertEquals(listOf("reported-bcg"), gateway.reportedIds())
    assertEquals(2, gateway.auditEvents.size)
    assertEquals(
      listOf("0", "0"),
      gateway.auditEvents.map { it.getValue("outcome").jsonPrimitive.content },
    )
  }

  @Test
  fun aCardThatFailsVerificationImportsNothingAndAuditsTheReason() = runTest {
    withReportedDoses()
    val linkCard = SignedCards.dvc(StandIn.XXK_LINK, nowSeconds, "vp-hepb-peds-monovalent")

    val outcome = assertIs<ScanOutcome.Failed>(service.scan(referral, linkCard))

    assertEquals(
      VerifyStep.TRUST_LOOKUP to FailureReason.KID_NOT_FOUND,
      outcome.step to outcome.reason,
    )
    assertEquals(1, gateway.transactions.size)
    assertEquals(setOf("AuditEvent"), gateway.transactions.single().map { it.type }.toSet())
    assertEquals(listOf("reported-bcg", "reported-hepb"), gateway.reportedIds().sorted())
    val audit = gateway.auditEvents.single()
    assertEquals("4", audit.getValue("outcome").jsonPrimitive.content)
    val codes =
      audit.getValue("subtype").jsonArray.map {
        it.jsonObject.getValue("code").jsonPrimitive.content
      }
    assertTrue("failed" in codes && "trust-lookup" in codes && "KidNotFound" in codes, "$codes")
  }

  @Test
  fun aVerifiedImportSatisfiesTheJ3aContract() = runTest {
    withReportedDoses()
    service.scan(referral, hepB)
    service.scan(referral, bcg)

    val gatewayScope = "Patient/$patientId"
    gateway.provenances.forEach {
      assertTrue(gatewayScope in it.references("target"), "the Provenance names the patient")
    }
    val stored =
      mapOf(
        "Immunization" to gateway.immunizations.values.toList(),
        "Provenance" to gateway.provenances,
      )
    val dates = Dates(LocalDate.parse("2026-11-14"))
    for (expectation in expectations.getValue(Step.J3A)) {
      val found = stored.getValue(expectation.query.type).map(::decode)
      assertTrue(expectation.satisfied(found, dates), expectation.label)
    }
  }

  private fun reported(id: String, product: String, card: String): JsonObject = buildJsonObject {
    put("resourceType", "Immunization")
    put("id", id)
    put("status", "completed")
    put("primarySource", false)
    put(
      "identifier",
      FhirJson.instance.parseToJsonElement(
        """[{"system":"${CertificateFingerprint.SYSTEM}","value":"${CertificateFingerprint.of(card)}"}]"""
      ),
    )
    put("patient", buildJsonObject { put("reference", "Patient/$patientId") })
    put(
      "vaccineCode",
      FhirJson.instance.parseToJsonElement(
        """{"coding":[{"system":"http://smart.who.int/trust-demo/CodeSystem/TrustDemoVaccineProduct","code":"$product"}]}"""
      ),
    )
    put("occurrenceDateTime", "2026-11-14")
  }

  private fun JsonObject.references(name: String): List<String> =
    getValue(name).jsonArray.map { it.jsonObject.getValue("reference").jsonPrimitive.content }

  private fun decode(json: JsonObject): Resource =
    FhirJson.instance.decodeFromJsonElement(Resource.serializer(), json)

  private fun JsonObject.flag(name: String): Boolean? =
    get(name)?.jsonPrimitive?.content?.toBooleanStrictOrNull()

  private fun resource(path: String): String =
    checkNotNull(CardVerificationTest::class.java.getResource(path)) { "missing $path" }.readText()
}

/** The gateway as the app sees it: it applies each transaction and answers the dedup search. */
private class FakeGateway {
  class Entry(val method: String, val url: String, val resource: JsonObject?) {
    val type: String
      get() = url.substringBefore('/')
  }

  val immunizations = linkedMapOf<String, JsonObject>()
  val provenances = mutableListOf<JsonObject>()
  val auditEvents = mutableListOf<JsonObject>()
  val transactions = mutableListOf<List<Entry>>()

  fun reportedIds(): List<String> =
    immunizations
      .filterValues { it["primarySource"]?.jsonPrimitive?.content == "false" }
      .keys
      .toList()

  val engine = MockEngine { request ->
    val ok = headersOf(HttpHeaders.ContentType, "application/fhir+json")
    if (request.method == HttpMethod.Get) {
      val wanted = request.url.parameters["identifier"].orEmpty().substringAfter('|')
      val found = immunizations.values.filter { dose -> wanted in dose.toString() }
      val entries = found.joinToString(",") { """{"resource":$it}""" }
      respond(
        """{"resourceType":"Bundle","type":"searchset","total":${found.size},"entry":[$entries]}""",
        HttpStatusCode.OK,
        ok,
      )
    } else {
      val bundle =
        FhirJson.instance.parseToJsonElement((request.body as TextContent).text).jsonObject
      val entries =
        bundle.getValue("entry").jsonArray.map {
          val entry = it.jsonObject
          val call = entry.getValue("request").jsonObject
          Entry(
            call.getValue("method").jsonPrimitive.content,
            call.getValue("url").jsonPrimitive.content,
            entry["resource"]?.jsonObject,
          )
        }
      transactions += listOf(entries)
      entries.forEach { entry ->
        val id = entry.url.substringAfter('/')
        when {
          entry.method == "DELETE" -> immunizations.remove(id)
          entry.type == "Immunization" -> immunizations[id] = entry.resource!!
          entry.type == "Provenance" -> provenances += entry.resource!!
          entry.type == "AuditEvent" -> auditEvents += entry.resource!!
        }
      }
      respond("""{"resourceType":"Bundle","type":"transaction-response"}""", HttpStatusCode.OK, ok)
    }
  }
}
