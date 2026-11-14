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
package dev.ohs.trustdemo.onesa.data.sharing

import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.feature.wallet.WalletViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.io.IOException

class ShareRetryTest {
  private val today = LocalDate.parse("2026-11-14")

  @Test
  fun aLostReplyIsRetriedWithTheSameKey() = runTest {
    val keys = mutableListOf<String?>()
    val engine = MockEngine { request ->
      keys += request.headers["Idempotency-Key"]
      if (keys.size == 1) throw IOException("the reply was lost")
      respond(
        replyFor(listOf("bounmy")),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/fhir+json"),
      )
    }
    val links = InMemoryLinks()
    val service =
      ShareService(
        repository = InMemorySampleFhirRepository(),
        accessToken = { "token" },
        httpClient = HttpClient(engine),
        baseUrl = "https://vhl.test:8443",
        links = links,
        expiry = { 1_000L },
        now = { 0 },
      )

    val outcome = service.share(ShareEntry(listOf("bounmy"), today, "0482", "0482"), today, "key-1")

    assertEquals(listOf<String?>("key-1", "key-1"), keys)
    assertEquals(listOf("c-bounmy"), assertIs<ShareOutcome.Issued>(outcome).consentIds)
    assertEquals(setOf("c-bounmy"), links.kept.keys)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun expiredLinksArePurgedWhenTheWalletOpens() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val links = InMemoryLinks()
      links.keep(
        listOf(
          IssuedLink("old", "bounmy", "Bounmy", "HC1:old", exp = 99),
          IssuedLink("new", "bounmy", "Bounmy", "HC1:new", exp = 101),
        )
      )
      val service =
        ShareService(
          repository = InMemorySampleFhirRepository(),
          accessToken = { "token" },
          httpClient = HttpClient(MockEngine { respond("") }),
          baseUrl = "https://vhl.test:8443",
          links = links,
          expiry = { null },
          now = { 100 },
        )

      WalletViewModel(service) { null }

      assertEquals(setOf("new"), links.kept.keys)
    } finally {
      Dispatchers.resetMain()
    }
  }
}
