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

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.trustdemo.onesa.data.repository.FhirEngineRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class ShareKeepsConsentsTest {
  private val today = LocalDate.parse("2026-11-14")
  private val people = listOf("somsak", "bounmy")
  private lateinit var fhirEngine: FhirEngine

  @BeforeTest
  fun setUp() = runTest {
    if (FhirEngineProvider.isNotInitialized()) {
      FhirEngineProvider.init(
        FhirEngineConfiguration(
          storageDirectory = Files.createTempDirectory("share-keeps-consents-test").toString()
        )
      )
    }
    fhirEngine = FhirEngineProvider.getInstance()
    fhirEngine.clearDatabase()
  }

  private val engine = MockEngine { request ->
    val body =
      if (request.method == HttpMethod.Post) replyFor(people)
      else
        """{"resourceType":"Bundle","type":"searchset","entry":[${
          (people.map { "c-$it" } + "older").joinToString(",") { """{"resource":${consent(it)}}""" }
        }]}"""
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/fhir+json"))
  }

  private fun consent(id: String) =
    """{"resourceType":"Consent","id":"$id","meta":{"versionId":"1"},"status":"active",
      "scope":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/consentscope","code":"patient-privacy"}]},
      "category":[{"coding":[{"system":"http://loinc.org","code":"59284-0"}]}],
      "patient":{"reference":"Patient/somsak"},"performer":[{"reference":"Patient/bounmy"}],
      "policyRule":{"coding":[{"system":"http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlConsent","code":"CONSENT-VHL-TREATMENT"}]},
      "provision":{"type":"permit","period":{"start":"2026-11-14","end":"2026-11-28"}}}"""

  @Test
  fun aShareIsListedAtOnceWithItsLinksAndNothingToUpload() = runTest {
    val repository = FhirEngineRepository(fhirEngine)
    val links = InMemoryLinks()
    val share =
      ShareService(
        repository = repository,
        accessToken = { "token" },
        httpClient = HttpClient(engine),
        baseUrl = "https://vhl.test:8443",
        links = links,
        fhirBaseUrl = "https://gateway.test/fhir/",
        expiry = { null },
        now = { 0 },
      )

    val outcome =
      share.share(ShareEntry(people, LocalDate.parse("2026-11-28"), "0482", "0482"), today, "key")

    assertIs<ShareOutcome.Issued>(outcome)
    val listed =
      SharedLinks(
          repository = repository,
          accessToken = { "token" },
          httpClient = HttpClient(engine),
          baseUrl = "https://vhl.test:8443",
          links = links,
          family = { "bounmy" },
          names = { null },
          refresh = { true },
          today = { today },
        )
        .list()
    assertEquals(setOf("c-somsak", "c-bounmy"), listed.map { it.consentId }.toSet())
    assertTrue(listed.all { it.kept }, "each share shows its QR")
    assertEquals(emptyList(), repository.unsynced("Consent"))
  }
}
