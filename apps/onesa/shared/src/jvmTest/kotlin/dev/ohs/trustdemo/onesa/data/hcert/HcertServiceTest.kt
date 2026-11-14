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
package dev.ohs.trustdemo.onesa.data.hcert

import dev.ohs.trustdemo.hcert.FailureReason
import dev.ohs.trustdemo.hcert.InMemoryTrustListStorage
import dev.ohs.trustdemo.hcert.TrustListCache
import dev.ohs.trustdemo.hcert.VerificationResult
import dev.ohs.trustdemo.hcert.VerifyStep
import dev.ohs.trustdemo.trustkit.StandIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class HcertServiceTest {
  private val service =
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
    )

  @Test
  fun `WHO XXR cards verify against the GDHCN DEV snapshot`() = runTest {
    val cards = Json.parseToJsonElement(resource("/dvc-qr-example-XXR.json")).jsonObject
    for (name in listOf("hepb1", "bcg")) {
      val card = cards.getValue("certificates").jsonObject.getValue(name).jsonObject
      val hc1 = card.getValue("hc1").jsonPrimitive.content
      val now = card.getValue("iat").jsonPrimitive.long + 3600
      val verified = assertIs<VerificationResult.Verified>(service.verifyCard(hc1, now), name)
      assertEquals("916ffa567c0f44a2", verified.hcert.dsc.kid, name)
      val asLink = assertIs<VerificationResult.Failed>(service.verifyLink(hc1, now), name)
      assertEquals(VerifyStep.TRUST_LOOKUP, asLink.failure.step, name)
    }
  }

  @Test
  fun `a card signed with a DEMO-VHL key fails the DEMO-DVC store`() = runTest {
    val now = 1_800_000_000L
    val hc1 = SignedCards.dvc(StandIn.XXK_LINK, now)
    val failure = assertIs<VerificationResult.Failed>(service.verifyCard(hc1, now)).failure
    assertEquals(
      VerifyStep.TRUST_LOOKUP to FailureReason.KID_NOT_FOUND,
      failure.step to failure.reason,
    )
    val link = service.verifyLink(hc1, now)
    assertNotEquals(VerifyStep.TRUST_LOOKUP, (link as? VerificationResult.Failed)?.failure?.step)
  }

  private fun resource(path: String): String =
    checkNotNull(HcertServiceTest::class.java.getResource(path)) { "missing $path" }.readText()
}
