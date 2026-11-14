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

import java.security.SignatureException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert

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
  fun aTamperedListIsRefused() {
    val tampered = TrustListWriter.localTestList().replace("DEMO-VHL", "DEMO-DVC")
    assertIs<HcertResult.Err<*>>(
      Hcert.trust
        .newTrustStore()
        .addVerifiedDidDocument(tampered, TestKeys.gdhcnSignerDidDocument())
    )
  }

  @Test
  fun standInKidsAreNotXxrsRealKid() =
    StandIn.entries.forEach { assertNotEquals("kW/6VnwPRKI=", TestKeys.kid(it)) }

  @Test
  fun clinicCertificateChainsToTheAnchorOnly() {
    val (_, cert) = ClinicCertificates.issueClinicViewer()
    cert.verify(TestKeys.certificate(StandIn.XXR_CLINIC_ANCHOR).publicKey)
    assertFailsWith<SignatureException> {
      cert.verify(TestKeys.certificate(StandIn.XXK_LINK).publicKey)
    }
  }

  @Test
  fun storyClockMapsJ3ToToday() {
    val clock = StoryClock(LocalDate.parse("2025-11-25"), LocalDate.parse("2026-11-13"))
    assertEquals(LocalDate.parse("2026-10-16"), clock.real(LocalDate.parse("2025-10-28")))
    assertEquals(353, clock.shiftDays())
  }
}
