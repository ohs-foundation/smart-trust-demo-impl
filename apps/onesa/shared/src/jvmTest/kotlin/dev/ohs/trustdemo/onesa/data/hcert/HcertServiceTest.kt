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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
    val hc1 = SignedCards.dvc(StandIn.XXU_LINK, now)
    val failure = assertIs<VerificationResult.Failed>(service.verifyCard(hc1, now)).failure
    assertEquals(
      VerifyStep.TRUST_LOOKUP to FailureReason.KID_NOT_FOUND,
      failure.step to failure.reason,
    )
    val link = service.verifyLink(hc1, now)
    assertNotEquals(VerifyStep.TRUST_LOOKUP, (link as? VerificationResult.Failed)?.failure?.step)
  }

  @Test
  fun aFailedRefreshKeepsTheLastGoodListAndSaysSo() = runTest {
    var up = true
    val localList = resource("/local-test-list.json")
    val local =
      PinnedTrustList(
        name = "Local test list",
        signer = BundledTrustLists::localSigner,
        cache =
          TrustListCache(
            InMemoryTrustListStorage(),
            url = "http://trust.gdhcn.example/local-test-list.json",
            httpClient =
              HttpClient(
                MockEngine {
                  if (up) respond(localList, HttpStatusCode.OK)
                  else respond("", HttpStatusCode.ServiceUnavailable)
                }
              ),
          ),
      )
    val service = HcertService(local, BundledTrustLists.gdhcnDev(InMemoryTrustListStorage()))
    val fetchedAt = 1_800_000_000L
    assertTrue(service.refreshLocalList(fetchedAt).isSuccess)
    val good = service.statuses(now = fetchedAt).first()
    assertEquals(null, good.lastFailure)

    up = false
    assertTrue(service.refreshLocalList(fetchedAt + 600).isFailure)

    val kept = service.statuses(now = fetchedAt + 600).first()
    assertEquals(fetchedAt, kept.fetchedAtEpochSeconds)
    assertEquals(good.entryCount, kept.entryCount)
    assertEquals(
      RefreshFailure(fetchedAt + 600, "Trust list fetch failed: HTTP 503"),
      kept.lastFailure,
    )
    assertFalse(kept.stale)
    val dayLater = service.statuses(now = fetchedAt + 25 * 3600)
    assertTrue(dayLater.first().stale)
    assertFalse(dayLater.last().stale, "the bundled snapshot is dated, never refreshed")
    val card =
      assertIs<VerificationResult.Verified>(
        service.verifyCard(SignedCards.dvc(StandIn.XXR_CARD, fetchedAt), fetchedAt)
      )
    assertEquals(
      ListFreshness(fetchedAt, stale = true),
      service.freshness(card.hcert.dsc, now = fetchedAt + 25 * 3600),
    )

    up = true
    assertTrue(service.refreshLocalList(fetchedAt + 900).isSuccess)
    assertEquals(null, service.statuses(now = fetchedAt + 900).first().lastFailure)
  }

  private fun resource(path: String): String =
    checkNotNull(HcertServiceTest::class.java.getResource(path)) { "missing $path" }.readText()
}
