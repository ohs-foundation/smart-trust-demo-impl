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
import dev.ohs.trustdemo.trustkit.StandIn
import dev.ohs.trustdemo.trustkit.TestKeys
import dev.ohs.trustdemo.trustkit.TrustListWriter
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MANIFEST_PATH = "/vhl/List/_search"
private const val ANCHOR_ENTRY = "trustlist:DEMO-VHL:XXR:CSCA"
private const val FORM_BODY =
  "_id=list-1&code=folder&status=current&patient.identifier=urn%3Aietf%3Arfc%3A3986%7CPatient%2Fp1" +
    "&recipient=Kesaro+clinic&passcode=482913"

class SignedRequestsTest {
  private var now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  private val clinic = ClinicCertificates.issueClinicViewer("CN=clinic-viewer.kesaro.example")
  private val releases = mutableListOf<ReleaseRequest>()
  private var list: String = TrustListWriter.localTestList()

  private val trustList =
    ClinicTrustList(
      source = { list },
      signer = TestKeys.gdhcnSignerDidDocument(),
      trustDomains =
        checkNotNull(javaClass.getResource("/CodeSystem-TrustDemoTrustDomain.json")).readText(),
    )

  private fun withService(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      val idp = TestIdentityProvider()
      vhlModule(
        VhlDeps(
          publicUrl = PUBLIC_URL,
          issuer = ISSUER,
          allowedClients = setOf(CLIENT),
          jwks = idp.provider,
          hapi = FhirBackend("http://hapi:8080/fhir", FakeHapi().engine),
          gateway =
            FhirBackend(
              "http://gateway:8080/fhir",
              FakeGateway(emptyMap(), emptyList()).engine,
              relaysCaller = true,
            ),
          signer =
            LinkSigner.fromPem(
              File("../../testkit/XXK_LINK.key.pem").readText(),
              File("../../testkit/XXK_LINK.crt.pem").readText(),
              "XK",
            ),
          passcodes = H2PasscodeStore("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"),
          clock = Clock.fixed(now, ZoneOffset.UTC),
          clinicAnchor = trustList,
          release = { request ->
            releases += request
            ReleaseOutcome.Denied
          },
        )
      )
    }
    block()
  }

  @Test
  fun aSignedRequestFromTheClinicIsAccepted() = withService {
    trustList.refresh()
    val response = post(sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray()))

    assertEquals(HttpStatusCode.Forbidden, response.status)
    val request = releases.single() as ReleaseRequest.Manifest
    assertEquals("clinic-viewer.kesaro.example", request.requester.name)
    assertEquals(kid(clinic.second), request.requester.kid)
    assertEquals("list-1", request.listId)
    assertEquals("482913", request.passcode)
    assertEquals("Kesaro clinic", request.recipient)
  }

  @Test
  fun aSignedDocumentRequestReachesTheSeamWithTheDocumentId() = withService {
    trustList.refresh()
    val response = get(sign("GET", "/vhl/Binary/doc-9", null))

    assertEquals(HttpStatusCode.Forbidden, response.status)
    val request = releases.single() as ReleaseRequest.Document
    assertEquals("doc-9", request.binaryId)
    assertEquals("clinic-viewer.kesaro.example", request.requester.name)
  }

  @Test
  fun anUnchainedCertificateIsRefused() = withService {
    trustList.refresh()
    val stranger = ClinicCertificates.issueClinicViewer(issuer = StandIn.UNKNOWN_LINK)

    assertRefused(post(sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray(), identity = stranger)))
  }

  @Test
  fun aCertificateChainedToADscEntryIsRefused() = withService {
    trustList.refresh()
    val underDsc = ClinicCertificates.issueClinicViewer(issuer = StandIn.XXK_LINK)
    val dsc = TestKeys.certificate(StandIn.XXK_LINK)
    assertEquals(dsc.subjectX500Principal, underDsc.second.issuerX500Principal)

    assertRefused(post(sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray(), identity = underDsc)))
  }

  @Test
  fun aStaleCreatedIsRefused() = withService {
    trustList.refresh()
    val stale =
      sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray(), created = now.epochSecond - 301)
    val early =
      sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray(), created = now.epochSecond + 301)
    val edge = sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray(), created = now.epochSecond - 300)

    assertRefused(post(stale))
    assertRefused(post(early))
    assertEquals(HttpStatusCode.Forbidden, post(edge).status)
  }

  @Test
  fun aTamperedBodyIsRefused() = withService {
    trustList.refresh()
    val signed = sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray())
    val tampered = signed.copy(body = FORM_BODY.replace("482913", "000000").toByteArray())

    assertRefused(post(tampered))
  }

  @Test
  fun aSignatureInputThatDiffersFromTheRebuiltOneIsRefused() = withService {
    trustList.refresh()
    val signed = sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray())
    val widened =
      signed.headers
        .getValue("Signature-Input")
        .replace("\"content-digest\")", "\"content-digest\" \"@query\")")
    val resigned = signed.copy(headers = signed.headers + ("Signature-Input" to widened))

    assertRefused(post(resigned))
  }

  @Test
  fun aRequestSignedForAnotherAuthorityIsRefused() = withService {
    trustList.refresh()

    assertRefused(
      post(sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray(), authority = "evil.example:8443"))
    )
  }

  @Test
  fun aRequestWithoutBodyProtectionIsRefused() = withService {
    trustList.refresh()
    val signed = sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray())
    val unprotected = signed.copy(headers = signed.headers - "Content-Digest")

    assertRefused(post(unprotected))
  }

  @Test
  fun aRequestWithoutHeadersIsRefused() = withService {
    trustList.refresh()

    assertRefused(client.post(MANIFEST_PATH) { setBody(FORM_BODY.toByteArray()) })
  }

  @Test
  fun everySignedRequestIsRefusedWhileNoTrustListHasLoaded() = withService {
    val response = post(sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray()))

    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    assertTrue(releases.isEmpty())
  }

  @Test
  fun aFailedRefreshKeepsTheLastGoodList() = withService {
    trustList.refresh()
    list = list.replace(ANCHOR_ENTRY, ANCHOR_ENTRY.replace("XXR", "XXK"))
    trustList.refresh()

    assertEquals(
      HttpStatusCode.Forbidden,
      post(sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray())).status,
    )
  }

  @Test
  fun aListWhoseProofDoesNotVerifyIsNeverLoaded() {
    list = list.replace(ANCHOR_ENTRY, ANCHOR_ENTRY.replace("XXR", "XXK"))
    runBlocking { trustList.refresh() }

    assertNull(trustList.current())
  }

  @Test
  fun theAnchorIsTheCscaEntryOfTheClinicParticipant() {
    runBlocking { trustList.refresh() }

    assertEquals(TestKeys.certificate(StandIn.XXR_CLINIC_ANCHOR), trustList.current())
  }

  @Test
  fun aListSignedByAnotherSignerIsRefused() {
    runBlocking { trustList.refresh() }
    val attacker = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    list =
      TrustListWriter.generate(
        StandIn.entries.associateWith { TestKeys.certificate(it) },
        attacker.private,
        now,
        "attackerNonce000000000000000000001",
      )
    runBlocking { trustList.refresh() }

    assertEquals(TestKeys.certificate(StandIn.XXR_CLINIC_ANCHOR), trustList.current())
  }

  @Test
  fun aListSignedByAnotherSignerIsNeverLoaded() {
    val attacker = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    list =
      TrustListWriter.generate(
        StandIn.entries.associateWith { TestKeys.certificate(it) },
        attacker.private,
        now,
        "attackerNonce000000000000000000001",
      )
    runBlocking { trustList.refresh() }

    assertNull(trustList.current())
  }

  @Test
  fun aCertificateWithTwoCommonNamesIsRefused() = withService {
    trustList.refresh()
    val ambiguous = ClinicCertificates.issueClinicViewer("CN=clinic-a,CN=clinic-b")

    assertRefused(post(sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray(), identity = ambiguous)))
  }

  @Test
  fun aCaFlaggedCertificateIsRefused() = withService {
    trustList.refresh()
    val ca = ClinicCertificates.issueClinicViewer(ca = true)

    assertRefused(post(sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray(), identity = ca)))
  }

  @Test
  fun anExpiredAnchorIsRefused() = withService {
    trustList.refresh()
    val anchor = TestKeys.certificate(StandIn.XXR_CLINIC_ANCHOR)
    val longLived = ClinicCertificates.issueClinicViewer(validFor = Duration.ofDays(36_500))
    now = anchor.notAfter.toInstant().plusSeconds(86_400)

    assertRefused(post(sign("POST", MANIFEST_PATH, FORM_BODY.toByteArray(), identity = longLived)))
  }

  private suspend fun ApplicationTestBuilder.post(request: Signed): HttpResponse =
    client.post(request.path) {
      apply(request)
      setBody(request.body)
    }

  private suspend fun ApplicationTestBuilder.get(request: Signed): HttpResponse =
    client.get(request.path) { apply(request) }

  private fun HttpRequestBuilder.apply(request: Signed) =
    request.headers.forEach { (name, value) -> header(name, value) }

  private suspend fun assertRefused(response: HttpResponse) {
    assertEquals(HttpStatusCode.Unauthorized, response.status)
    val outcome = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val issue = outcome.getValue("issue").jsonArray.single()
    assertEquals("OperationOutcome", outcome.getValue("resourceType").jsonPrimitive.content)
    assertEquals("security", issue.jsonObject.getValue("code").jsonPrimitive.content)
    assertTrue(releases.isEmpty(), "a refused request must not reach the release decision")
  }

  private fun sign(
    method: String,
    path: String,
    body: ByteArray?,
    identity: Pair<PrivateKey, X509Certificate> = clinic,
    created: Long = now.epochSecond,
    authority: String = AUTHORITY,
  ): Signed = signRequest(method, path, body, identity, created, authority)
}
