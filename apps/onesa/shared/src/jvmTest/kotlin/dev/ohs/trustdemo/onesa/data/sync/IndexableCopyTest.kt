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
package dev.ohs.trustdemo.onesa.data.sync

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.repository.FhirEngineRepository
import dev.ohs.trustdemo.onesa.data.sharing.InMemoryLinks
import dev.ohs.trustdemo.onesa.data.sharing.IssuedLink
import dev.ohs.trustdemo.onesa.data.sharing.SharedLinks
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IndexableCopyTest {
  private lateinit var engine: FhirEngine

  @BeforeTest
  fun setUp() = runTest {
    if (FhirEngineProvider.isNotInitialized()) {
      FhirEngineProvider.init(
        FhirEngineConfiguration(
          storageDirectory = Files.createTempDirectory("fhir-engine-indexable-test").toString()
        )
      )
    }
    engine = FhirEngineProvider.getInstance()
    engine.clearDatabase()
  }

  private val consent =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      """{"resourceType":"Consent","id":"c-somsak","status":"active",
        "meta":{"versionId":"1","lastUpdated":"2026-11-14T14:56:23.611+00:00"},
        "dateTime":"2026-11-14T14:56:23.495871466Z",
        "sourceAttachment":{"contentType":"application/json","title":"Explicit consent confirmed on the family wallet when the link was created"},
        "scope":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/consentscope","code":"patient-privacy"}]},
        "category":[{"coding":[{"system":"http://loinc.org","code":"59284-0"}]}],
        "patient":{"reference":"Patient/somsak"},"performer":[{"reference":"Patient/bounmy"}],
        "policyRule":{"coding":[{"system":"http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlConsent","code":"CONSENT-VHL-TREATMENT"}]},
        "provision":{"type":"permit","period":{"start":"2026-11-14","end":"2026-11-28"}}}""",
    )

  @Test
  fun theEngineStoresAConsentWithASourceAttachment() = runTest {
    val repository = FhirEngineRepository(engine)

    repository.storeAsSynced(listOf(IndexableCopy.of(consent)))

    assertNotNull(repository.get("Consent", "c-somsak"))
  }

  @Test
  fun aStoredConsentIsListedAsASharedLink() = runTest {
    val repository = FhirEngineRepository(engine)
    val links = InMemoryLinks()
    links.keep(listOf(IssuedLink("c-somsak", "somsak", "Somsak", "HC1:one", exp = 9)))
    repository.storeAsSynced(listOf(IndexableCopy.of(consent)))

    val listed =
      SharedLinks(
          repository = repository,
          accessToken = { "token" },
          httpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
          baseUrl = "https://vhl.test:8443",
          links = links,
          family = { "bounmy" },
          names = { null },
          refresh = { true },
          today = { LocalDate.parse("2026-11-14") },
        )
        .list()

    assertEquals(listOf("c-somsak"), listed.map { it.consentId })
  }

  @Test
  fun onlyAConsentLosesItsSourceAttachment() {
    val json =
      FhirJson.instance
        .encodeToJsonElement(Resource.serializer(), IndexableCopy.of(consent))
        .jsonObject

    assertEquals(false, "sourceAttachment" in json)
    assertEquals(
      "Patient/bounmy",
      json.getValue("performer").jsonArray[0].jsonObject.getValue("reference").jsonPrimitive.content,
    )
  }
}
