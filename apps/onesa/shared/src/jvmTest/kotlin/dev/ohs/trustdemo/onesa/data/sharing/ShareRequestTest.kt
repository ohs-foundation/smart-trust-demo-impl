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
import dev.ohs.trustdemo.vhl.BadRequest
import dev.ohs.trustdemo.vhl.ShareRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class ShareRequestTest {
  private val today = LocalDate.parse("2026-11-14")
  private val people = listOf("bounmy", "khamla", "somsak")

  @Test
  fun theShareRequestIsWhatTheServiceAccepts() {
    val body =
      ShareRequestBody.of(people, start = today, end = LocalDate.parse("2026-11-28"), "0482")

    val request = ShareRequest.parse(body)

    assertEquals(people, request.people)
    assertEquals("2026-11-14", request.periodStart)
    assertEquals("2026-11-28", request.periodEnd)
    assertEquals("0482", request.passcode)

    val last = ShareRequestBody.lastDay(today)
    ShareRequest.parse(ShareRequestBody.of(people, today, last, "0482"))
    val tooLate = ShareRequestBody.of(people, today, LocalDate.parse("2027-11-14"), "0482")
    assertFailsWith<BadRequest> { ShareRequest.parse(tooLate) }
  }

  @Test
  fun thePasscodeStaysAString() = runTest {
    var sent = ""
    val engine = MockEngine { request ->
      sent = (request.body as TextContent).text
      respond(
        replyFor(people),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/fhir+json"),
      )
    }
    val service =
      ShareService(
        repository = InMemorySampleFhirRepository(),
        accessToken = { "token" },
        httpClient = HttpClient(engine),
        baseUrl = "https://vhl.test:8443",
        links = InMemoryLinks(),
        expiry = { null },
        now = { 0 },
      )

    val outcome =
      service.share(
        ShareEntry(people, LocalDate.parse("2026-11-28"), "0482", again = "0482"),
        today,
        "key-1",
      )

    assertIs<ShareOutcome.Issued>(outcome)
    assertTrue(""""valueString": "0482"""" in sent || """"valueString":"0482"""" in sent)
    assertEquals("0482", ShareRequest.parse(sent).passcode)
    assertEquals(
      ShareProblem.PasscodesDiffer,
      ShareEntry(people, today, "0482", "482").problem(today),
    )
    assertEquals(
      ShareProblem.PasscodeLength,
      ShareEntry(people, today, "048", "048").problem(today),
    )
    assertEquals(null, ShareEntry(people, today, "0482", "0482").problem(today))
  }
}

internal fun replyFor(people: List<String>): String =
  """{"resourceType":"Parameters","parameter":[${
    people.joinToString(",") { id ->
      """{"name":"link","part":[
        {"name":"person","valueReference":{"reference":"Patient/$id"}},
        {"name":"link","valueString":"HC1:$id"},
        {"name":"consent","valueReference":{"reference":"Consent/c-$id"}}]}"""
    }
  }]}"""

internal class InMemoryLinks : LinkStore {
  val kept = mutableMapOf<String, IssuedLink>()

  override suspend fun keep(links: List<IssuedLink>) = links.forEach { kept[it.consentId] = it }

  override suspend fun get(consentIds: List<String>) = consentIds.mapNotNull { kept[it] }

  override suspend fun remove(consentId: String) {
    kept.remove(consentId)
  }

  override suspend fun purgeExpired(nowEpochSeconds: Long) {
    kept.values.removeAll { it.exp <= nowEpochSeconds }
  }

  override suspend fun clear() = kept.clear()
}
