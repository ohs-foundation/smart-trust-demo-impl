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
package dev.ohs.trustdemo.vhl

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.cert.CertificateFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import who.smart.trust.HcertResult
import who.smart.trust.core.DEFAULT_LIMITS
import who.smart.trust.core.Hcert
import who.smart.trust.payloads.Payload
import who.smart.trust.verifier.VerificationResult
import who.smart.trust.verifier.VerifierOptions
import who.smart.trust.vhl.Decrypted

private val TESTKIT = File("../../testkit")

class GenerateVhlTest {
  private val now = Instant.now()
  private val periodEnd = now.plusSeconds(14 * 86_400)
  private val idp = TestIdentityProvider()
  private val household = group("bounmy", "khamla", "somsak")
  private val patients =
    listOf(
      patient("bounmy", "SENGMANY", "Bounmy"),
      patient("khamla", "SENGMANY", "Khamla"),
      patient("somsak", "SENGMANY", "Somsak"),
      patient("stranger", "OTHER", "Person"),
    )
  private val gateway =
    FakeGateway(
      groups = mapOf("bounmy" to household, "khamla" to household),
      patients = patients,
      immunizations = listOf(immunization("hepb", "somsak"), immunization("bcg", "somsak")),
    )
  private val hapi = FakeHapi()
  private val kept = mutableListOf<Pair<String, String>>()

  private fun withService(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      vhlModule(
        VhlDeps(
          publicUrl = PUBLIC_URL,
          issuer = ISSUER,
          jwks = idp.provider,
          hapi = FhirBackend("http://hapi:8080/fhir", hapi.engine),
          gateway = FhirBackend("http://gateway:8080/fhir", gateway.engine),
          signer =
            LinkSigner.fromPem(
              File(TESTKIT, "XXK_LINK.key.pem").readText(),
              File(TESTKIT, "XXK_LINK.crt.pem").readText(),
              "XK",
            ),
          passcodes = PasscodeStore { consentId, passcode -> kept += consentId to passcode },
          clock = Clock.fixed(now, ZoneOffset.UTC),
        )
      )
    }
    block()
  }

  private fun parameters(vararg people: String, passcode: String = "482913"): String =
    buildJsonObject {
        put("resourceType", "Parameters")
        putJsonArray("parameter") {
          people.forEach { person ->
            add(
              buildJsonObject {
                put("name", "person")
                putJsonObject("valueReference") { put("reference", "Patient/$person") }
              }
            )
          }
          add(
            buildJsonObject {
              put("name", "purpose")
              put("valueCode", "TREAT")
            }
          )
          add(
            buildJsonObject {
              put("name", "period")
              putJsonObject("valuePeriod") {
                put("start", now.toString())
                put("end", periodEnd.toString())
              }
            }
          )
          add(
            buildJsonObject {
              put("name", "passcode")
              put("valueString", passcode)
            }
          )
        }
      }
      .toString()

  private suspend fun ApplicationTestBuilder.generate(token: String?, body: String): HttpResponse =
    client.post("/vhl/\$generate-vhl") {
      token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
      header(HttpHeaders.ContentType, ContentType.parse("application/fhir+json").toString())
      setBody(body)
    }

  private fun bounmyToken() = idp.token("Patient/bounmy")

  private class Link(val person: String, val hc1: String, val records: Map<String, String>)

  private fun links(response: String): List<Link> =
    Json.parseToJsonElement(response).jsonObject.getValue("parameter").jsonArray.objects().map {
      val parts = it.getValue("part").jsonArray.objects().associateBy { p -> p.text("name") }
      fun reference(name: String) =
        parts.getValue(name).getValue("valueReference").jsonObject.text("reference")!!
      Link(
        person = reference("person"),
        hc1 = parts.getValue("link").text("valueString")!!,
        records =
          listOf("consent", "bundle", "binary", "documentReference", "list").associateWith {
            reference(it)
          },
      )
    }

  private fun written(): List<JsonObject> = hapi.transactions.flatMap { it.resources() }

  private fun trustStore() =
    Hcert.trust.newTrustStore().apply {
      assertIs<HcertResult.Ok<Int>>(
        addVerifiedDidDocument(
          File(TESTKIT, "local-test-list.json").readText(),
          File(TESTKIT, "gdhcn-signer-did.json").readText(),
        )
      )
    }

  private fun verified(hc1: String) =
    assertIs<VerificationResult.Verified>(
      Hcert.verifier.verify(
        hc1,
        trustStore(),
        Hcert.subclaims.newRegistry(),
        now.epochSecond,
        VerifierOptions(DEFAULT_LIMITS, clockSkewSeconds = 300),
      )
    )

  private fun linkPayload(hc1: String) =
    assertIs<Payload.Vhl>(verified(hc1).value.entries.single().payload).value

  private fun decryptedIps(link: Link): JsonObject {
    val key = linkPayload(link.hc1).key
    val binaryId = link.records.getValue("binary").substringAfter('/')
    val binary = written().first { it.type() == "Binary" && it.text("id") == binaryId }
    val jwe = String(Base64.getDecoder().decode(binary.text("data")))
    val decrypted = assertIs<HcertResult.Ok<Decrypted>>(Hcert.vhl.decrypt(jwe, key)).value
    return Json.parseToJsonElement(String(decrypted.content)).jsonObject
  }

  @Test
  fun aParentWithNoImmunizationsGetsAValidIpsAndALink() = withService {
    val response = generate(bounmyToken(), parameters("bounmy"))

    assertEquals(HttpStatusCode.OK, response.status)
    val link = links(response.bodyAsText()).single()
    assertEquals("Patient/bounmy", link.person)
    assertEquals(
      listOf("Binary", "Bundle", "Consent", "DocumentReference", "List"),
      written().map { it.type() }.sorted(),
    )

    val ips = decryptedIps(link)
    assertEquals("document", ips.text("type"))
    val inBundle = ips.resources()
    assertEquals("Composition", inBundle.first().type())
    assertEquals(listOf("Composition", "Consent", "Patient"), inBundle.map { it.type() }.sorted())
    val immunizations =
      inBundle.first().getValue("section").jsonArray.objects().single {
        it
          .getValue("code")
          .jsonObject
          .getValue("coding")
          .jsonArray
          .objects()
          .single()
          .text("code") == "11369-6"
      }
    assertNull(immunizations["entry"])
    assertTrue(immunizations.containsKey("emptyReason"))

    val payload = linkPayload(link.hc1)
    assertEquals("P", payload.flag)
    assertEquals(periodEnd.epochSecond, payload.exp)
    assertTrue(payload.url.startsWith("$PUBLIC_URL/vhl/List/_search?"), payload.url)
    assertTrue(payload.url.contains("_id=${link.records.getValue("list").substringAfter('/')}"))
    assertEquals(
      payload,
      (Hcert.vhl.fromVhlink(Hcert.vhl.toVhlink(payload)) as HcertResult.Ok).value,
    )
  }

  @Test
  fun eachPersonPickedGetsTheirOwnConsentAndLink() = withService {
    val token = bounmyToken()
    val response = generate(token, parameters("bounmy", "somsak", passcode = "4821"))

    assertEquals(HttpStatusCode.OK, response.status)
    val issued = links(response.bodyAsText())
    assertEquals(listOf("Patient/bounmy", "Patient/somsak"), issued.map { it.person })
    assertEquals(2, hapi.transactions.size)
    assertEquals(2, issued.map { it.hc1 }.toSet().size)
    assertEquals(2, issued.map { linkPayload(it.hc1).key }.toSet().size)

    val consents = written().filter { it.type() == "Consent" }
    assertEquals(
      listOf("Patient/bounmy", "Patient/somsak"),
      consents.map { it.getValue("patient").jsonObject.text("reference") },
    )
    assertEquals(
      issued.map { it.records.getValue("consent") },
      consents.map { "Consent/${it.text("id")}" },
    )
    val ids = written().map { it.text("id")!! }
    assertEquals(ids.size, ids.toSet().size)
    assertTrue(ids.none { it.startsWith("TrustDemo") })
    assertEquals(issued.map { it.records.getValue("consent").substringAfter('/') to "4821" }, kept)

    val immunizationsPerPerson =
      issued.map { decryptedIps(it).resources().count { r -> r.type() == "Immunization" } }
    assertEquals(listOf(0, 2), immunizationsPerPerson)

    assertTrue(gateway.requests.isNotEmpty())
    assertTrue(gateway.requests.all { it.authorization == "Bearer $token" })
    assertTrue(gateway.requests.none { "everything" in it.url || "_include" in it.url })
  }

  @Test
  fun aPersonOutsideTheCallersHouseholdIsRefused() = withService {
    val response = generate(bounmyToken(), parameters("bounmy", "stranger"))

    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertEquals(emptyList(), hapi.transactions)
    assertEquals(emptyList(), kept)
  }

  @Test
  fun theLinkVerifiesAgainstTheLocalListUnderDemoVhl() = withService {
    val link = links(generate(bounmyToken(), parameters("bounmy")).bodyAsText()).single()

    assertTrue(link.hc1.startsWith("HC1:"))
    verified(link.hc1)
    val dsc =
      CertificateFactory.getInstance("X.509")
        .generateCertificate(File(TESTKIT, "XXK_LINK.crt.pem").inputStream())
    val candidates = trustStore().candidates(Hcert.cose.kidFromDsc(dsc.encoded))
    assertEquals(listOf("DEMO-VHL"), candidates.map { it.domain })
    assertFalse(candidates.any { it.domain == "DEMO-DVC" })
  }

  @Test
  fun aRequestWithoutAValidTokenIsRefused() = withService {
    val body = parameters("bounmy")
    val otherKey = TestIdentityProvider.newKeyPair()
    val refused =
      listOf(
        null,
        "not-a-token",
        idp.token("Patient/bounmy", issuer = "http://elsewhere.test/realms/other"),
        idp.token("Patient/bounmy", expiresAt = now.minusSeconds(600)),
        idp.token("Patient/bounmy", signedWith = otherKey),
        idp.token(fhirUser = null),
      )

    refused.forEach { assertEquals(HttpStatusCode.Unauthorized, generate(it, body).status, "$it") }

    assertEquals(emptyList(), hapi.transactions)
    assertNotEquals(HttpStatusCode.Unauthorized, generate(bounmyToken(), body).status)
  }
}
