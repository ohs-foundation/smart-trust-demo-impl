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
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import who.smart.trust.vhl.VhlPayload

private val TESTKIT = File("../../testkit")

class GenerateVhlTest {
  private val now = Instant.now()
  private val periodEnd = now.plusSeconds(14 * 86_400)
  private val idp = TestIdentityProvider()
  private val household = group("bounmy", "khamla", "somsak")
  private val patients =
    listOf(
      patient("bounmy", "SENGMANY", "Bounmy", managedBy = "rp-bounmy"),
      patient("khamla", "SENGMANY", "Khamla"),
      patient("somsak", "SENGMANY", "Somsak"),
      patient("stranger", "OTHER", "Person"),
    )
  private var gateway =
    FakeGateway(
      groups = mapOf("rp-bounmy" to household),
      patients = patients,
      immunizations = listOf(immunization("hepb", "somsak"), immunization("bcg", "somsak")),
    )
  private val hapi =
    FakeHapi(
      listOf(
        clinicalRecord("Condition", "htn", "khamla", "subject"),
        clinicalRecord("AllergyIntolerance", "nka", "khamla"),
        clinicalRecord("MedicationStatement", "amlodipine", "khamla", "subject"),
        clinicalRecord("Observation", "bp", "khamla", "subject"),
      )
    )
  private var signer =
    LinkSigner.fromPem(
      File(TESTKIT, "XXU_LINK.key.pem").readText(),
      File(TESTKIT, "XXU_LINK.crt.pem").readText(),
      "XU",
    )
  private val database = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
  private var passcodes: PasscodeStore = H2PasscodeStore(database)

  private fun withService(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      vhlModule(
        VhlDeps(
          publicUrl = PUBLIC_URL,
          issuer = ISSUER,
          allowedClients = setOf(CLIENT, "trust-demo-cli"),
          jwks = idp.provider,
          hapi = FhirBackend("http://hapi:8080/fhir", hapi.engine),
          gateway = FhirBackend("http://gateway:8080/fhir", gateway.engine, relaysCaller = true),
          signer = signer,
          passcodes = passcodes,
          clock = Clock.fixed(now, ZoneOffset.UTC),
        )
      )
    }
    block()
  }

  private fun parameters(
    vararg people: String,
    passcode: String = "482913",
    purpose: String = "TREAT",
    start: String = now.toString(),
    end: String = periodEnd.toString(),
  ): String =
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
              put("valueCode", purpose)
            }
          )
          add(
            buildJsonObject {
              put("name", "period")
              putJsonObject("valuePeriod") {
                put("start", start)
                put("end", end)
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

  private suspend fun ApplicationTestBuilder.generate(
    token: String?,
    body: String,
    key: String? = null,
  ): HttpResponse =
    client.post("/vhl/\$generate-vhl") {
      token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
      key?.let { header("Idempotency-Key", it) }
      header(HttpHeaders.ContentType, ContentType.parse("application/fhir+json").toString())
      setBody(body)
    }

  private fun bounmyToken() = idp.token("Patient/bounmy")

  private class Link(val person: String, val hc1: String, val records: Map<String, String>) {
    fun consentId() = records.getValue("consent").substringAfter('/')
  }

  private suspend fun ApplicationTestBuilder.withdraw(
    token: String?,
    consent: String,
  ): HttpResponse =
    client.post("/vhl/\$withdraw") {
      token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
      header(HttpHeaders.ContentType, ContentType.parse("application/fhir+json").toString())
      setBody(
        buildJsonObject {
            put("resourceType", "Parameters")
            putJsonArray("parameter") {
              add(
                buildJsonObject {
                  put("name", "consent")
                  putJsonObject("valueReference") { put("reference", "Consent/$consent") }
                }
              )
            }
          }
          .toString()
      )
    }

  private fun status(consentId: String) = hapi.stored.getValue("Consent/$consentId").text("status")

  private suspend fun ApplicationTestBuilder.share(passcode: String, vararg people: String) =
    links(generate(bounmyToken(), parameters(*people, passcode = passcode)).bodyAsText())

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

  private fun sectionsByCode(ips: JsonObject): Map<String, JsonObject> =
    ips.resources().first().getValue("section").jsonArray.objects().associateBy {
      it.getValue("code").jsonObject.getValue("coding").jsonArray.objects().single().text("code")!!
    }

  @Test
  fun aPersonsProblemsAllergiesMedicationsAndVitalsAreInTheirIps() = withService {
    val link = links(generate(bounmyToken(), parameters("khamla")).bodyAsText()).single()
    val ips = decryptedIps(link)
    val bundled = ips.resources().map { "${it.type()}/${it.text("id")}" }.toSet()
    val sections = sectionsByCode(ips)

    assertEquals(
      listOf("48765-2", "10160-0", "11450-4", "8716-3", "11369-6", "42348-3"),
      sections.keys.toList(),
    )
    val entries =
      mapOf(
        "48765-2" to "AllergyIntolerance/nka",
        "10160-0" to "MedicationStatement/amlodipine",
        "11450-4" to "Condition/htn",
        "8716-3" to "Observation/bp",
      )
    entries.forEach { (code, reference) ->
      val referenced =
        sections.getValue(code).getValue("entry").jsonArray.objects().map { it.text("reference") }
      assertEquals(listOf(reference), referenced)
      assertTrue(reference in bundled)
      assertNull(sections.getValue(code)["emptyReason"])
    }
  }

  @Test
  fun aPersonWithoutThoseRecordsKeepsTheUnavailableSections() = withService {
    val link = links(generate(bounmyToken(), parameters("bounmy")).bodyAsText()).single()
    val sections = sectionsByCode(decryptedIps(link))

    assertEquals(
      listOf("48765-2", "10160-0", "11450-4", "11369-6", "42348-3"),
      sections.keys.toList(),
    )
    listOf("48765-2", "10160-0", "11450-4").forEach {
      assertNull(sections.getValue(it)["entry"])
      assertTrue("unavailable" in sections.getValue(it).getValue("emptyReason").toString())
    }
  }

  @Test
  fun aRetriedShareReturnsTheSameLinks() = withService {
    val body = parameters("bounmy", "somsak")
    val first = generate(bounmyToken(), body, key = "share-1")
    val retried = generate(bounmyToken(), body, key = "share-1")

    assertEquals(HttpStatusCode.OK, retried.status)
    assertEquals(first.bodyAsText(), retried.bodyAsText())
    assertEquals(1, hapi.transactions.size)
    assertEquals(2, written().count { it.type() == "Consent" })

    generate(bounmyToken(), body)
    assertEquals(2, hapi.transactions.size)
  }

  @Test
  fun aReusedKeyWithAnotherBodyIsRefused() = withService {
    generate(bounmyToken(), parameters("bounmy"), key = "share-1")
    val refused = generate(bounmyToken(), parameters("bounmy", "somsak"), key = "share-1")

    assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
    assertEquals(1, hapi.transactions.size)
  }

  @Test
  fun eachPersonPickedGetsTheirOwnConsentAndLink() = withService {
    val token = bounmyToken()
    val response = generate(token, parameters("bounmy", "somsak", passcode = "4821"))

    assertEquals(HttpStatusCode.OK, response.status)
    val issued = links(response.bodyAsText())
    assertEquals(listOf("Patient/bounmy", "Patient/somsak"), issued.map { it.person })
    assertEquals(1, hapi.transactions.size)
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
    issued.forEach { assertEquals(PasscodeCheck.MATCH, passcodes.check(it.consentId(), "4821")) }

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
  }

  @Test
  fun anotherHouseholdsParentSharesTwoOfItsMembers() = withService {
    gateway =
      FakeGateway(
        groups = mapOf("rp-bounmy" to household, "rp-noy" to group("noy", "kham", "lattana")),
        patients =
          patients +
            listOf(
              patient("noy", "VONGSA", "Noy", managedBy = "rp-noy"),
              patient("kham", "VONGSA", "Kham"),
              patient("lattana", "VONGSA", "Lattana"),
            ),
        immunizations = listOf(immunization("penta", "lattana"), immunization("hepb", "somsak")),
      )
    val noy = idp.token("Patient/noy")

    val response = generate(noy, parameters("noy", "lattana", passcode = "7310"))

    assertEquals(HttpStatusCode.OK, response.status)
    val issued = links(response.bodyAsText())
    assertEquals(listOf("Patient/noy", "Patient/lattana"), issued.map { it.person })
    val consents = written().filter { it.type() == "Consent" }
    assertEquals(
      listOf("Patient/noy", "Patient/lattana"),
      consents.map { it.getValue("patient").jsonObject.text("reference") },
    )
    assertEquals(
      listOf("Patient/noy", "Patient/noy"),
      consents.map { it.getValue("performer").jsonArray.objects().single().text("reference") },
    )
    issued.forEach {
      verified(it.hc1)
      assertEquals(PasscodeCheck.MATCH, passcodes.check(it.consentId(), "7310"))
    }
    assertEquals(
      listOf(0, 1),
      issued.map { decryptedIps(it).resources().count { r -> r.type() == "Immunization" } },
    )

    assertEquals(HttpStatusCode.Forbidden, generate(noy, parameters("noy", "somsak")).status)
    assertEquals(1, hapi.transactions.size)
  }

  @Test
  fun theLinkVerifiesAgainstTheLocalListUnderDemoVhl() = withService {
    val link = links(generate(bounmyToken(), parameters("bounmy")).bodyAsText()).single()

    assertTrue(link.hc1.startsWith("HC1:"))
    verified(link.hc1)
    val dsc =
      CertificateFactory.getInstance("X.509")
        .generateCertificate(File(TESTKIT, "XXU_LINK.crt.pem").inputStream())
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

  @Test
  fun aTokenForAnotherClientIsRefused() = withService {
    val other = idp.token("Patient/bounmy", azp = "some-other-client")

    assertEquals(HttpStatusCode.Unauthorized, generate(other, parameters("bounmy")).status)
    assertEquals(emptyList(), hapi.transactions)
  }

  @Test
  fun anyPurposeButTreatmentIsRefused() = withService {
    val response = generate(bounmyToken(), parameters("bounmy", purpose = "HMARKT"))

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(emptyList(), hapi.transactions)
  }

  @Test
  fun anInvertedMalformedOrOverlongPeriodIsRefused() = withService {
    val periods =
      listOf(
        periodEnd.toString() to now.toString(),
        "next week" to periodEnd.toString(),
        now.toString() to now.plusSeconds(366 * 86_400L).toString(),
      )

    periods.forEach { (start, end) ->
      val response = generate(bounmyToken(), parameters("bounmy", start = start, end = end))
      assertEquals(HttpStatusCode.BadRequest, response.status, "$start to $end")
    }
    assertEquals(emptyList(), hapi.transactions)
  }

  @Test
  fun aFailureForTheSecondPersonWritesNothing() = withService {
    val real = signer
    var signed = 0
    signer =
      object : LinkSigner {
        override fun sign(link: VhlPayload, issuedAt: Instant, expires: Instant): String {
          check(++signed < 2) { "the second signature fails" }
          return real.sign(link, issuedAt, expires)
        }
      }

    val response = generate(bounmyToken(), parameters("bounmy", "somsak"))

    assertEquals(HttpStatusCode.InternalServerError, response.status)
    assertEquals(emptyList(), hapi.transactions)
  }

  @Test
  fun aRefusedWriteIsABadGatewayAndAllPeopleShareOneTransaction() = withService {
    val ok = generate(bounmyToken(), parameters("bounmy", "somsak"))
    assertEquals(HttpStatusCode.OK, ok.status)
    assertEquals(1, hapi.transactions.size)
    assertEquals(10, hapi.transactions.single().resources().size)

    hapi.transactions.clear()
    hapi.failWith = HttpStatusCode.Forbidden
    val refused = generate(bounmyToken(), parameters("bounmy", "somsak"))

    assertEquals(HttpStatusCode.BadGateway, refused.status)
    assertEquals(emptyList(), hapi.transactions)
  }

  @Test
  fun anOverReturningGatewayLeaksNoOtherPersonsImmunizations() = withService {
    gateway =
      FakeGateway(
        gateway.groups,
        patients,
        listOf(immunization("hepb", "somsak")),
        overReturns = true,
      )

    val link = links(generate(bounmyToken(), parameters("bounmy")).bodyAsText()).single()

    assertEquals(0, decryptedIps(link).resources().count { it.type() == "Immunization" })
  }

  @Test
  fun anInactiveMemberCannotBePicked() = withService {
    gateway =
      FakeGateway(
        mapOf("rp-bounmy" to group("bounmy", "khamla", "somsak", inactive = setOf("somsak"))),
        patients,
      )

    assertEquals(HttpStatusCode.Forbidden, generate(bounmyToken(), parameters("somsak")).status)
    assertEquals(emptyList(), hapi.transactions)
  }

  @Test
  fun passcode0482RoundTripsAsAString() = withService {
    val link = share("0482", "bounmy").single()

    assertEquals(PasscodeCheck.MATCH, passcodes.check(link.consentId(), "0482"))
    assertEquals(0, passcodes.wrongAttempts(link.consentId()))
    assertEquals(PasscodeCheck.WRONG, passcodes.check(link.consentId(), "482"))
    assertEquals(1, passcodes.wrongAttempts(link.consentId()))
    assertEquals(PasscodeCheck.UNKNOWN, passcodes.check("no-such-consent", "0482"))
  }

  @Test
  fun twoActiveSharesForOnePersonKeepSeparateConsentsAndPasscodes() = withService {
    val first = share("1111", "bounmy").single()
    val second = share("2222", "bounmy").single()

    assertNotEquals(first.consentId(), second.consentId())
    assertEquals("active", status(first.consentId()))
    assertEquals("active", status(second.consentId()))
    assertEquals(PasscodeCheck.MATCH, passcodes.check(first.consentId(), "1111"))
    assertEquals(PasscodeCheck.WRONG, passcodes.check(first.consentId(), "2222"))
    assertEquals(PasscodeCheck.MATCH, passcodes.check(second.consentId(), "2222"))
    assertEquals(PasscodeCheck.WRONG, passcodes.check(second.consentId(), "1111"))
    assertEquals(1, passcodes.wrongAttempts(first.consentId()))
    assertEquals(1, passcodes.wrongAttempts(second.consentId()))
  }

  @Test
  fun withdrawingOneShareLeavesTheOtherWorking() = withService {
    val first = share("1111", "bounmy").single()
    val second = share("2222", "bounmy").single()

    assertEquals(HttpStatusCode.OK, withdraw(bounmyToken(), first.consentId()).status)

    assertEquals("inactive", status(first.consentId()))
    assertEquals(PasscodeCheck.UNKNOWN, passcodes.check(first.consentId(), "1111"))
    assertEquals("active", status(second.consentId()))
    assertEquals(PasscodeCheck.MATCH, passcodes.check(second.consentId(), "2222"))
    assertEquals(listOf("Consent/${first.consentId()}"), hapi.puts)
  }

  @Test
  fun noFhirRecordContainsThePasscodeOrItsHash() = withService {
    val response = generate(bounmyToken(), parameters("bounmy", "somsak", passcode = "482913"))
    val everything = written().joinToString { it.toString() } + response.bodyAsText()
    val rows =
      DriverManager.getConnection(database).use { connection ->
        connection.createStatement().use { statement ->
          statement.executeQuery("SELECT hash, salt FROM passcode").use { result ->
            buildList {
              while (result.next()) add(result.getBytes("hash") to result.getBytes("salt"))
            }
          }
        }
      }

    assertEquals(2, rows.size)
    assertFalse("482913" in everything)
    rows
      .flatMap { it.toList() }
      .forEach { bytes ->
        val encodings =
          listOf(
            bytes.joinToString("") { "%02x".format(it) },
            Base64.getEncoder().encodeToString(bytes),
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
          )
        encodings.forEach { assertFalse(it in everything, it) }
      }
  }

  @Test
  fun onlyTheSharerCanWithdraw() = withService {
    val link = share("1111", "bounmy").single()
    val consent = link.consentId()

    assertEquals(HttpStatusCode.Unauthorized, withdraw(null, consent).status)
    assertEquals(
      HttpStatusCode.Unauthorized,
      withdraw(idp.token("Patient/bounmy", azp = "another-client"), consent).status,
    )
    assertEquals(HttpStatusCode.Forbidden, withdraw(idp.token("Patient/khamla"), consent).status)
    assertEquals("active", status(consent))
    assertEquals(PasscodeCheck.MATCH, passcodes.check(consent, "1111"))
    assertEquals(emptyList(), hapi.puts)

    assertEquals(HttpStatusCode.NotFound, withdraw(bounmyToken(), "no-such-consent").status)
    assertEquals(HttpStatusCode.OK, withdraw(bounmyToken(), consent).status)
    assertEquals("inactive", status(consent))
  }

  @Test
  fun aStoreFailureInactivatesTheNewConsents() = withService {
    passcodes =
      object : PasscodeStore by H2PasscodeStore(database) {
        override suspend fun keep(consentId: String, passcode: String) = error("disk full")
      }

    val response = generate(bounmyToken(), parameters("bounmy", "somsak"))

    assertEquals(HttpStatusCode.BadGateway, response.status)
    val consents = hapi.stored.values.filter { it.type() == "Consent" }
    assertEquals(2, consents.size)
    assertEquals(listOf("inactive", "inactive"), consents.map { it.text("status") })
    assertFalse("482913" in response.bodyAsText())
  }

  @Test
  fun concurrentWrongGuessesAreAllCounted() = withService {
    val consent = share("1111", "bounmy").single().consentId()

    val answers = coroutineScope {
      (1..6).map { async { passcodes.check(consent, "9999") } }.awaitAll()
    }

    assertEquals(3, answers.count { it == PasscodeCheck.WRONG })
    assertEquals(3, answers.count { it == PasscodeCheck.EXHAUSTED })
    assertEquals(3, passcodes.wrongAttempts(consent))
    assertEquals(PasscodeCheck.EXHAUSTED, passcodes.check(consent, "1111"))
    assertEquals(3, passcodes.wrongAttempts(consent))
  }

  @Test
  fun withdrawPreservesTheConsentsOtherFields() = withService {
    val consent = share("1111", "bounmy").single().consentId()
    val before = hapi.stored.getValue("Consent/$consent")

    assertEquals(HttpStatusCode.OK, withdraw(bounmyToken(), consent).status)

    val after = hapi.stored.getValue("Consent/$consent")
    assertEquals("inactive", after.text("status"))
    assertEquals(JsonObject(before - "status"), JsonObject(after - "status"))
    assertEquals(listOf<String?>("W/\"1\""), hapi.ifMatches)
  }

  @Test
  fun aConcurrentChangeMakesWithdrawConflict() = withService {
    val consent = share("1111", "bounmy").single().consentId()
    hapi.conflictOnPut = true

    assertEquals(HttpStatusCode.Conflict, withdraw(bounmyToken(), consent).status)

    assertEquals("active", status(consent))
    assertEquals(emptyList(), hapi.puts)
    assertEquals(PasscodeCheck.MATCH, passcodes.check(consent, "1111"))
  }

  @Test
  fun withdrawingAnInactiveConsentWritesNothingAndDropsThePasscode() = withService {
    val consent = share("1111", "bounmy").single().consentId()
    hapi.stored["Consent/$consent"] =
      JsonObject(hapi.stored.getValue("Consent/$consent") + ("status" to JsonPrimitive("inactive")))

    assertEquals(HttpStatusCode.OK, withdraw(bounmyToken(), consent).status)

    assertEquals(emptyList(), hapi.puts)
    assertEquals(PasscodeCheck.UNKNOWN, passcodes.check(consent, "1111"))
  }
}
