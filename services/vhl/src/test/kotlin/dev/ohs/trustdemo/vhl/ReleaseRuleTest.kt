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

import dev.ohs.trustdemo.trustkit.ClinicCertificates
import dev.ohs.trustdemo.trustkit.TestKeys
import dev.ohs.trustdemo.trustkit.TrustListWriter
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert

private const val MANIFEST_PATH = "/vhl/List/_search"
private const val PASSCODE = "0482"
private const val JWE = "eyJhbGciOiJkaXIifQ..iv.ciphertext.tag"
private const val REFUSAL_SYSTEM = "http://ohs.dev/fhir/trust-demo/CodeSystem/vhl-refusal-reason"
private const val IG_REFUSAL_SYSTEM =
  "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlRefusalReason"
private const val ACCESS_PROFILE =
  "http://smart.who.int/trust-demo/StructureDefinition/TrustDemoOnesaVhlAccess"
private const val ATTEMPTS_LEFT =
  "http://ohs.dev/fhir/trust-demo/StructureDefinition/passcode-attempts-left"

class ReleaseRuleTest {
  private var now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  private val clock =
    object : Clock() {
      override fun getZone(): ZoneId = ZoneOffset.UTC

      override fun withZone(zone: ZoneId): Clock = this

      override fun instant(): Instant = now
    }
  private val clinic = ClinicCertificates.issueClinicViewer("CN=clinic-viewer.kesaro.example")
  private val otherClinic = ClinicCertificates.issueClinicViewer("CN=clinic-b.kesaro.example")
  private val hapi = FakeHapi()
  private val hapiBackend = FhirBackend("http://hapi:8080/fhir", hapi.engine)
  private val database = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
  private val passcodes = H2PasscodeStore(database)
  private val trustList =
    ClinicTrustList(
      source = { TrustListWriter.localTestList() },
      signer = TestKeys.gdhcnSignerDidDocument(),
      trustDomains =
        checkNotNull(javaClass.getResource("/CodeSystem-TrustDemoTrustDomain.json")).readText(),
    )

  private class Seeded(val share: Share, val passcode: String) {
    val consentId = share.consent.id()
    val manifestBody =
      "_id=${share.listId}&code=folder&status=current&recipient=Kesaro+paediatric+clinic&passcode=%s"
  }

  private fun withService(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      vhlModule(
        VhlDeps(
          publicUrl = PUBLIC_URL,
          issuer = ISSUER,
          allowedClients = setOf(CLIENT),
          jwks = TestIdentityProvider().provider,
          hapi = hapiBackend,
          gateway =
            FhirBackend(
              "http://gateway:8080/fhir",
              FakeGateway(emptyMap(), emptyList()).engine,
              relaysCaller = true,
            ),
          signer =
            LinkSigner.fromPem(
              File("../../testkit/XXU_LINK.key.pem").readText(),
              File("../../testkit/XXU_LINK.crt.pem").readText(),
              "XU",
            ),
          passcodes = passcodes,
          clock = clock,
          clinicAnchor = trustList,
          release = ReleaseRule(hapiBackend, passcodes, H2ReleaseStore(database), clock),
        )
      )
    }
    trustList.refresh()
    block()
  }

  private suspend fun seed(
    passcode: String? = PASSCODE,
    status: String = "active",
    end: Instant = now.plus(Duration.ofDays(7)),
  ): Seeded {
    val records = Records(PUBLIC_URL, now)
    val share =
      records.prepare(
        "bounmy",
        patient("somsak", "SENGMANY", "Somsak"),
        emptyList(),
        ClinicalRecords(),
        ShareRequest(listOf("somsak"), now.minusSeconds(60).toString(), end.toString(), "unused"),
      )
    hapiBackend.transaction(records.transaction(listOf(share to JWE)))
    if (status != "active") {
      val consent = hapi.stored.getValue("Consent/${share.consent.id()}")
      hapi.stored["Consent/${share.consent.id()}"] =
        JsonObject(consent + ("status" to JsonPrimitive(status)))
    }
    passcode?.let { passcodes.keep(share.consent.id(), it) }
    return Seeded(share, passcode ?: PASSCODE)
  }

  private fun manifestRequest(
    seeded: Seeded,
    sent: String?,
    identity: Pair<java.security.PrivateKey, java.security.cert.X509Certificate> = clinic,
  ): Signed {
    val body =
      seeded.manifestBody.format(sent ?: "").let {
        if (sent == null) it.removeSuffix("&passcode=") else it
      }
    return signRequest("POST", MANIFEST_PATH, body.toByteArray(), identity, now.epochSecond)
  }

  private fun documentRequest(
    seeded: Seeded,
    identity: Pair<java.security.PrivateKey, java.security.cert.X509Certificate> = clinic,
  ): Signed =
    signRequest("GET", "/vhl/Binary/${seeded.share.binaryId}", null, identity, now.epochSecond)

  private suspend fun ApplicationTestBuilder.post(request: Signed): HttpResponse =
    client.post(request.path) {
      apply(request)
      setBody(request.body)
    }

  private suspend fun ApplicationTestBuilder.get(request: Signed): HttpResponse =
    client.get(request.path) { apply(request) }

  private fun HttpRequestBuilder.apply(request: Signed) =
    request.headers.forEach { (name, value) -> header(name, value) }

  private fun audits(): List<JsonObject> =
    hapi.stored.filterKeys { it.startsWith("AuditEvent/") }.values.toList()

  private fun JsonObject.reason(): String? =
    getValue("subtype")
      .jsonArray
      .objects()
      .firstOrNull { it.text("system") == REFUSAL_SYSTEM || it.text("system") == IG_REFUSAL_SYSTEM }
      ?.text("code")

  private suspend fun HttpResponse.refusal(): Pair<String?, String?> {
    val coding =
      Json.parseToJsonElement(bodyAsText())
        .jsonObject
        .getValue("issue")
        .jsonArray
        .single()
        .jsonObject["details"]
        ?.jsonObject
        ?.getValue("coding")
        ?.jsonArray
        ?.single()
        ?.jsonObject
    return coding?.text("system") to coding?.text("code")
  }

  private fun JsonObject.entities(): List<String> =
    getValue("entity").jsonArray.objects().map {
      it.getValue("what").jsonObject.text("reference")!!
    }

  private class Case(
    val name: String,
    val priorWrong: Int = 0,
    val status: String = "active",
    val expired: Boolean = false,
    val sent: String? = PASSCODE,
    val reason: String?,
    val wrongAfter: Int,
  )

  @Test
  fun theReleaseRuleDecidesFirstHitInOrder() {
    val cases =
      listOf(
        Case(
          "R5 stops the right passcode",
          priorWrong = 3,
          reason = "attempts-exhausted",
          wrongAfter = 3,
        ),
        Case(
          "R5 comes before R1",
          priorWrong = 3,
          status = "inactive",
          reason = "attempts-exhausted",
          wrongAfter = 3,
        ),
        Case("R1 withdrawn", status = "inactive", reason = "withdrawn", wrongAfter = 0),
        Case(
          "R1 comes before R2 and R3",
          status = "inactive",
          expired = true,
          sent = "9999",
          reason = "withdrawn",
          wrongAfter = 0,
        ),
        Case("R2 expired", expired = true, reason = "expired", wrongAfter = 0),
        Case(
          "R2 comes before R3",
          expired = true,
          sent = "9999",
          reason = "expired",
          wrongAfter = 0,
        ),
        Case("R3 wrong passcode", sent = "0483", reason = "wrong-passcode", wrongAfter = 1),
        Case("R3 no passcode", sent = null, reason = "wrong-passcode", wrongAfter = 1),
        Case("R4 after two wrong guesses", priorWrong = 2, reason = null, wrongAfter = 2),
        Case("R4 release", reason = null, wrongAfter = 0),
      )
    cases.forEach { case ->
      hapi.stored.clear()
      withService {
        val seeded =
          seed(
            status = case.status,
            end = if (case.expired) now.minus(Duration.ofHours(1)) else now.plus(Duration.ofDays(7)),
          )
        repeat(case.priorWrong) { passcodes.check(seeded.consentId, "0000") }

        val response = post(manifestRequest(seeded, case.sent))

        val audit = audits().single()
        assertEquals(case.reason, audit.reason(), case.name)
        assertEquals(case.wrongAfter, passcodes.wrongAttempts(seeded.consentId), case.name)
        assertEquals(
          ACCESS_PROFILE,
          audit
            .getValue("meta")
            .jsonObject
            .getValue("profile")
            .jsonArray
            .single()
            .jsonPrimitive
            .content,
          case.name,
        )
        assertEquals("110112", audit.getValue("type").jsonObject.text("code"), case.name)
        assertEquals(now.toString(), audit.text("recorded"), case.name)
        assertFalse(audit.id().startsWith("TrustDemo"), case.name)
        if (case.reason == null) {
          assertEquals(HttpStatusCode.OK, response.status, case.name)
          assertEquals("0", audit.text("outcome"), case.name)
          assertEquals(
            listOf(
              "Patient/somsak",
              "Consent/${seeded.consentId}",
              "Bundle/${seeded.share.ips.id()}",
            ),
            audit.entities(),
            case.name,
          )
        } else {
          assertEquals(HttpStatusCode.Forbidden, response.status, case.name)
          val system =
            if (case.reason == "attempts-exhausted") REFUSAL_SYSTEM else IG_REFUSAL_SYSTEM
          assertEquals(system to case.reason, response.refusal(), case.name)
          assertEquals("4", audit.text("outcome"), case.name)
          assertEquals(
            listOf("Patient/somsak", "Consent/${seeded.consentId}"),
            audit.entities(),
            case.name,
          )
        }
      }
    }
  }

  @Test
  fun theReleasedManifestIsWhatParseManifestReads() = withService {
    val seeded = seed()

    val response = post(manifestRequest(seeded, PASSCODE))

    val parsed = Hcert.vhl.parseManifest(response.bodyAsText())
    val documents =
      assertIs<HcertResult.Ok<List<who.smart.trust.vhl.ManifestDocument>>>(parsed).value
    assertEquals(
      listOf("$PUBLIC_URL/vhl/Binary/${seeded.share.binaryId}"),
      documents.map { it.url },
    )
    assertEquals(listOf("application/jose"), documents.map { it.contentType })
  }

  @Test
  fun noAuditEventForAnUnsignedRequest() = withService {
    val seeded = seed()
    val unsigned =
      manifestRequest(seeded, PASSCODE).let {
        Signed(it.path, it.body, mapOf("Content-Type" to FORM))
      }
    val tampered =
      manifestRequest(seeded, PASSCODE).let {
        it.copy(body = it.body!!.decodeToString().replace(PASSCODE, "9999").toByteArray())
      }

    assertEquals(HttpStatusCode.Unauthorized, post(unsigned).status)
    assertEquals(HttpStatusCode.Unauthorized, post(tampered).status)
    assertTrue(audits().isEmpty())
    assertEquals(0, passcodes.wrongAttempts(seeded.consentId))

    assertEquals(HttpStatusCode.OK, post(manifestRequest(seeded, PASSCODE)).status)
    assertEquals(1, audits().size)
  }

  @Test
  fun documentOnlyAfterAReleaseForTheSameCertificate() = withService {
    val seeded = seed()
    val unreleased = seed()

    assertEquals(HttpStatusCode.Forbidden, get(documentRequest(seeded)).status)
    assertEquals("4", audits().single().text("outcome"))

    assertEquals(HttpStatusCode.OK, post(manifestRequest(seeded, PASSCODE)).status)
    hapi.stored.keys.filter { it.startsWith("AuditEvent/") }.forEach { hapi.stored.remove(it) }

    assertEquals(HttpStatusCode.Forbidden, get(documentRequest(seeded, otherClinic)).status)
    val unreleasedResponse = get(documentRequest(unreleased))
    assertEquals(HttpStatusCode.Forbidden, unreleasedResponse.status)
    assertEquals(null to null, unreleasedResponse.refusal())
    assertEquals(listOf("4", "4"), audits().map { it.text("outcome") })
    hapi.stored.keys.filter { it.startsWith("AuditEvent/") }.forEach { hapi.stored.remove(it) }

    now = now.plus(Duration.ofMinutes(9)).plusSeconds(59)
    val served = get(documentRequest(seeded))
    assertEquals(HttpStatusCode.OK, served.status)
    assertEquals(JWE, served.bodyAsText())
    assertEquals("application/jose", served.headers[HttpHeaders.ContentType])
    assertEquals("0", audits().single().text("outcome"))

    now = now.plusSeconds(2)
    assertEquals(HttpStatusCode.Forbidden, get(documentRequest(seeded)).status)
  }

  @Test
  fun wrongAndUnknownLookTheSameToTheRequester() = withService {
    val wrong = seed()
    val unknown = seed(passcode = null)

    val wrongResponse = post(manifestRequest(wrong, "0483"))
    val unknownResponse = post(manifestRequest(unknown, "0483"))
    val unknownRight = post(manifestRequest(unknown, PASSCODE))

    assertEquals(HttpStatusCode.Forbidden, wrongResponse.status)
    assertEquals(HttpStatusCode.Forbidden, unknownResponse.status)
    assertEquals(HttpStatusCode.Forbidden, unknownRight.status)
    assertEquals(wrongResponse.bodyAsText(), unknownResponse.bodyAsText())
    assertEquals(wrongResponse.bodyAsText(), unknownRight.bodyAsText())
    assertEquals(IG_REFUSAL_SYSTEM to "wrong-passcode", wrongResponse.refusal())
    assertEquals(
      listOf("wrong-passcode", "wrong-passcode", "wrong-passcode"),
      audits().map { it.reason() },
    )
    assertEquals(1, passcodes.wrongAttempts(wrong.consentId))
    assertEquals(0, passcodes.wrongAttempts(unknown.consentId))
  }

  @Test
  fun aWrongPasscodeSaysHowManyAttemptsAreLeft() = withService {
    val seeded = seed()

    val issues =
      (1..4).map {
        Json.parseToJsonElement(post(manifestRequest(seeded, "0483")).bodyAsText())
          .jsonObject
          .getValue("issue")
          .jsonArray
          .single()
          .jsonObject
      }

    assertEquals(
      listOf(2, 1, 0, null),
      issues.map { issue ->
        issue["extension"]
          ?.jsonArray
          ?.objects()
          ?.single { it.text("url") == ATTEMPTS_LEFT }
          ?.text("valueUnsignedInt")
          ?.toInt()
      },
    )
    assertEquals(
      listOf(
        "release denied; 2 of 3 passcode attempts left",
        "release denied; 1 of 3 passcode attempts left",
        "release denied; 0 of 3 passcode attempts left",
        "release denied",
      ),
      issues.map { it.text("diagnostics") },
    )
  }

  @Test
  fun aReplayedWrongRequestIsCounted() = withService {
    val seeded = seed()
    val replayed = manifestRequest(seeded, "0483")

    repeat(3) { assertEquals(HttpStatusCode.Forbidden, post(replayed).status) }
    assertEquals(3, passcodes.wrongAttempts(seeded.consentId))
    assertEquals(HttpStatusCode.Forbidden, post(manifestRequest(seeded, PASSCODE)).status)

    val reasons = audits().map { it.reason() }
    assertEquals(3, reasons.count { it == "wrong-passcode" })
    assertEquals(1, reasons.count { it == "attempts-exhausted" })
  }

  @Test
  fun parallelWrongGuessesNeverExceedThree() = withService {
    val seeded = seed()
    val guess = manifestRequest(seeded, "0483")

    val statuses = coroutineScope { (1..20).map { async { post(guess).status } }.awaitAll() }

    assertTrue(statuses.all { it == HttpStatusCode.Forbidden })
    val reasons = audits().map { it.reason() }
    assertEquals(20, reasons.size)
    assertEquals(3, passcodes.wrongAttempts(seeded.consentId))
    assertEquals(3, reasons.count { it == "wrong-passcode" })
    assertEquals(17, reasons.count { it == "attempts-exhausted" })
  }

  @Test
  fun aWithdrawalBetweenManifestAndDocumentStopsTheDocument() = withService {
    val seeded = seed()
    assertEquals(HttpStatusCode.OK, post(manifestRequest(seeded, PASSCODE)).status)
    assertEquals(HttpStatusCode.OK, get(documentRequest(seeded)).status)
    val consent = hapi.stored.getValue("Consent/${seeded.consentId}")
    hapi.stored["Consent/${seeded.consentId}"] =
      JsonObject(consent + ("status" to JsonPrimitive("inactive")))
    hapi.stored.keys.filter { it.startsWith("AuditEvent/") }.forEach { hapi.stored.remove(it) }

    val refused = get(documentRequest(seeded))
    assertEquals(HttpStatusCode.Forbidden, refused.status)
    assertEquals(IG_REFUSAL_SYSTEM to "withdrawn", refused.refusal())

    val audit = audits().single()
    assertEquals("4", audit.text("outcome"))
    assertEquals("withdrawn", audit.reason())
  }

  @Test
  fun aDocumentExpiringBetweenManifestAndDocumentStopsTheDocument() = withService {
    val seeded = seed(end = now.plusSeconds(120))
    assertEquals(HttpStatusCode.OK, post(manifestRequest(seeded, PASSCODE)).status)
    hapi.stored.keys.filter { it.startsWith("AuditEvent/") }.forEach { hapi.stored.remove(it) }
    now = now.plusSeconds(180)

    assertEquals(HttpStatusCode.Forbidden, get(documentRequest(seeded)).status)

    assertEquals("expired", audits().single().reason())
  }

  @Test
  fun aDeniedFetchOfAnUnknownDocumentIsNotAudited() = withService {
    val seeded = seed()
    val unknown = signRequest("GET", "/vhl/Binary/no-such-binary", null, clinic, now.epochSecond)

    val response = get(unknown)
    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertEquals(null to null, response.refusal())
    assertTrue(audits().isEmpty())

    assertEquals(HttpStatusCode.Forbidden, get(documentRequest(seeded)).status)
    assertEquals(1, audits().size)
  }
}
