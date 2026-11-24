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
package dev.ohs.trustdemo.trustkit

import java.security.KeyPair
import java.security.SignatureException
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert
import who.smart.trust.trust.TrustError

class TrustKitTest {
  private fun igResource(name: String) =
    checkNotNull(javaClass.getResource("/$name")) { "missing $name" }.readText()

  @Test
  fun localListLoadsWithItsProof() {
    val store = Hcert.trust.newTrustStore()
    store.loadTrustDomains(igResource("CodeSystem-TrustDemoTrustDomain.json"))
    assertEquals(
      HcertResult.Ok(3),
      store.addVerifiedDidDocument(
        TrustListWriter.localTestList(),
        TestKeys.gdhcnSignerDidDocument(),
      ),
    )
  }

  @Test
  fun listedKidsMatchHcertsIndexing() {
    val store = Hcert.trust.newTrustStore()
    store.addVerifiedDidDocument(TrustListWriter.localTestList(), TestKeys.gdhcnSignerDidDocument())
    listOf(StandIn.XXR_CARD, StandIn.XXU_LINK, StandIn.XXR_CLINIC_ANCHOR).forEach {
      val kid = Hcert.cose.kidFromDsc(TestKeys.certificate(it).encoded)
      assertEquals(1, store.candidates(kid).size, it.name)
    }
  }

  @Test
  fun aTamperedListIsRefused() {
    val tampered = TrustListWriter.localTestList().replace("DEMO-VHL", "DEMO-DVC")
    val result =
      Hcert.trust
        .newTrustStore()
        .addVerifiedDidDocument(tampered, TestKeys.gdhcnSignerDidDocument())
    assertIs<TrustError.ProofInvalid>((result as HcertResult.Err).error)
  }

  @Test
  fun clinicCertificateChainsToTheAnchorOnly() {
    val anchor = TestKeys.certificate(StandIn.XXR_CLINIC_ANCHOR)
    val (key, cert) = ClinicCertificates.issueClinicViewer()
    cert.verify(anchor.publicKey)
    assertEquals(anchor.subjectX500Principal, cert.issuerX500Principal)
    validatePath(cert, anchor)

    assertFailsWith<SignatureException> {
      cert.verify(TestKeys.certificate(StandIn.XXU_LINK).publicKey)
    }
    val forged =
      ClinicCertificates.certificate(
        issuer = anchor.subjectX500Principal.encoded,
        subject = "CN=clinic-viewer.kesaro.example",
        subjectKey = KeyPair(cert.publicKey, key),
        signer = TestKeys.privateKey(StandIn.XXU_LINK),
        notBefore = Instant.now().minusSeconds(60),
        notAfter = Instant.now().plusSeconds(3600),
        ca = false,
      )
    assertFailsWith<CertPathValidatorException> { validatePath(forged, anchor) }
  }

  private fun validatePath(leaf: X509Certificate, anchor: X509Certificate) {
    val params =
      PKIXParameters(setOf(TrustAnchor(anchor, null))).apply { isRevocationEnabled = false }
    CertPathValidator.getInstance("PKIX")
      .validate(CertificateFactory.getInstance("X.509").generateCertPath(listOf(leaf)), params)
  }

  @Test
  fun aListSignedByAnotherKeyIsRefused() {
    val unsigned =
      TrustListWriter.localTestList().let { Json.parseToJsonElement(it).jsonObject - "proof" }
    val forged =
      DidProofSigner.sign(
        JsonObject(unsigned).toString(),
        TestKeys.privateKey(StandIn.XXR_CARD),
        TrustListWriter.SIGNER_ID,
        Instant.parse("2026-11-14T00:00:00Z"),
        "forgedNonce",
      )
    val store = Hcert.trust.newTrustStore()
    val result = store.addVerifiedDidDocument(forged, TestKeys.gdhcnSignerDidDocument())
    assertIs<TrustError.ProofInvalid>((result as HcertResult.Err).error)
    assertEquals(0, store.size())
  }

  @Test
  fun storyClockMapsJ3ToToday() {
    val clock = StoryClock(LocalDate.parse("2026-01-06"), LocalDate.parse("2026-11-13"))
    assertEquals(LocalDate.parse("2026-09-04"), clock.real(LocalDate.parse("2025-10-28")))
    assertEquals(311, clock.shiftDays())
  }
}
