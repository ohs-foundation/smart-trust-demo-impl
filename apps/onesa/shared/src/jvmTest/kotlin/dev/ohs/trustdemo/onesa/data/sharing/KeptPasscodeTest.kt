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

import dev.ohs.trustdemo.onesa.auth.Session
import dev.ohs.trustdemo.onesa.auth.SessionRepository
import dev.ohs.trustdemo.onesa.auth.UserInfo
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** A share's passcode is kept encrypted beside its links, and goes when they go. */
class KeptPasscodeTest {
  private val people = listOf("bounmy", "khamla", "somsak")
  private val ids = people.map { "c-$it" }
  private val passcode = "4829"
  private val json = headersOf(HttpHeaders.ContentType, "application/fhir+json")

  @BeforeTest
  fun signIn() = runTest {
    SessionRepository.save(Session("access", null, null, 300, 1_000, UserInfo(subject = "u1")))
  }

  @AfterTest fun signOut() = runTest { SessionRepository.clear() }

  @Test
  fun thePasscodeIsKeptWithTheLinksAndSentOnlyToGenerateVhl() = runTest {
    val sent = mutableListOf<Pair<String, String>>()
    val engine = MockEngine { request ->
      sent += request.url.toString() to request.body.toByteArray().decodeToString()
      if (request.url.encodedPath.endsWith("\$generate-vhl")) {
        respond(replyFor(people), HttpStatusCode.OK, json)
      } else {
        respond("""{"resourceType":"Bundle","type":"searchset"}""", HttpStatusCode.OK, json)
      }
    }
    val service =
      ShareService(
        repository = InMemorySampleFhirRepository(),
        accessToken = { "token" },
        httpClient = HttpClient(engine),
        baseUrl = "https://vhl.test:8443",
        links = SecureLinkStore,
        fhirBaseUrl = "https://gateway.test/fhir/",
        expiry = { 2_000_000_000 },
        now = { 0 },
      )

    val outcome =
      service.share(
        ShareEntry(people, LocalDate.parse("2026-11-28"), passcode, again = passcode),
        LocalDate.parse("2026-11-14"),
        "key-1",
      )

    assertIs<ShareOutcome.Issued>(outcome)
    assertEquals(true, outcome.saved)
    assertEquals(List(3) { passcode }, SecureLinkStore.get(ids).map { it.passcode })
    assertEquals(2, sent.size)
    assertEquals(
      listOf("https://vhl.test:8443/vhl/\$generate-vhl"),
      sent.filter { (url, body) -> passcode in url || passcode in body }.map { it.first },
    )
  }

  @Test
  fun withdrawingAShareDropsItsPasscode() = runTest {
    SecureLinkStore.keep(ids.map { link(it, exp = 2_000_000_000) })
    val shared =
      SharedLinks(
        repository = InMemorySampleFhirRepository(),
        accessToken = { "token" },
        httpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, json) }),
        baseUrl = "https://vhl.test:8443",
        links = SecureLinkStore,
        family = { "bounmy" },
        names = { null },
        refresh = { true },
      )

    assertEquals(WithdrawOutcome.Withdrawn(), shared.withdraw("c-somsak"))

    assertEquals(emptyList(), SecureLinkStore.get(listOf("c-somsak")))
    assertEquals(
      listOf(passcode, passcode),
      SecureLinkStore.get(listOf("c-bounmy", "c-khamla")).map { it.passcode },
    )
  }

  @Test
  fun anExpiredShareDropsItsPasscode() = runTest {
    SecureLinkStore.keep(listOf(link("c-old", exp = 100), link("c-new", exp = 200)))

    SecureLinkStore.purgeExpired(100)

    assertEquals(
      listOf("c-new"),
      SecureLinkStore.get(listOf("c-old", "c-new")).map { it.consentId },
    )
    assertEquals(passcode, SecureLinkStore.get(listOf("c-new")).single().passcode)
  }

  private fun link(consentId: String, exp: Long) =
    IssuedLink(consentId, consentId.removePrefix("c-"), "Name", "HC1:$consentId", exp, passcode)
}
