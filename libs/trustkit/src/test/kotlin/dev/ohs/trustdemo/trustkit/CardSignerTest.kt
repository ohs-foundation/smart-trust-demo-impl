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

import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import who.smart.trust.HcertResult
import who.smart.trust.core.DEFAULT_LIMITS
import who.smart.trust.core.Hcert
import who.smart.trust.payloads.Payload
import who.smart.trust.trust.TrustStore
import who.smart.trust.types.FailureReason
import who.smart.trust.types.Step
import who.smart.trust.verifier.VerificationResult
import who.smart.trust.verifier.VerifierOptions

class CardSignerTest {
  private val clock13Nov = StoryClock(LocalDate.parse("2025-11-25"), LocalDate.parse("2026-11-13"))
  private val now = Instant.parse("2026-11-13T00:00:00Z").epochSecond
  private val json = Json { ignoreUnknownKeys = true }

  private fun igResource(name: String) =
    checkNotNull(javaClass.getResource("/$name.json")) { "missing $name" }.readText()

  private fun igImmunization(id: String) =
    json.decodeFromString(Immunization.serializer(), igResource("Immunization-$id"))

  private fun igPatient(id: String) =
    json.decodeFromString(Patient.serializer(), igResource("Patient-$id"))

  private fun localStore() =
    Hcert.trust.newTrustStore().apply {
      loadTrustDomains(igResource("CodeSystem-TrustDemoTrustDomain"))
      addVerifiedDidDocument(TrustListWriter.localTestList(), TestKeys.gdhcnSignerDidDocument())
    }

  private fun devStore() =
    Hcert.trust.newTrustStore().apply {
      val verified =
        addVerifiedDidDocument(
          TestKeys.testkitText("who/root-did.json"),
          TestKeys.testkitText("who/WHO-signing-did.json"),
        )
      assertIs<HcertResult.Ok<Int>>(verified)
    }

  private fun verification(text: String, store: TrustStore) =
    Hcert.verifier.verify(
      text,
      store,
      Hcert.subclaims.newRegistry(),
      now,
      VerifierOptions(DEFAULT_LIMITS, clockSkewSeconds = 300),
    )

  private fun verifies(text: String, store: TrustStore) =
    verification(text, store) is VerificationResult.Verified

  private fun decodedVaccinationDate(text: String): String {
    val verified = assertIs<VerificationResult.Verified>(verification(text, localStore()))
    return assertIs<Payload.DvcMin>(verified.value.entries.single().payload).value.vx.dv
  }

  @Test
  fun cardVerifiesAgainstTheLocalListAndNotTheDevList() {
    val hc1 =
      CardSigner.sign(
        igImmunization("TrustDemoJ1HepB"),
        igPatient("TrustDemoTaifafekiSomsak"),
        clock13Nov,
      )
    assertTrue(verifies(hc1, localStore()))
    val failure = assertIs<VerificationResult.Failed>(verification(hc1, devStore())).value
    assertEquals(Step.TRUST_LOOKUP to FailureReason.KID_NOT_FOUND, failure.step to failure.reason)
  }

  @Test
  fun cardCarriesTheShiftedDates() {
    val hc1 =
      CardSigner.sign(
        igImmunization("TrustDemoJ1HepB"),
        igPatient("TrustDemoTaifafekiSomsak"),
        clock13Nov,
      )
    assertEquals("2026-10-16", decodedVaccinationDate(hc1))
  }
}
