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
import dev.ohs.trustdemo.onesa.util.FhirJson
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WholeSecondsTest {
  private lateinit var engine: FhirEngine

  @BeforeTest
  fun setUp() = runTest {
    if (FhirEngineProvider.isNotInitialized()) {
      FhirEngineProvider.init(
        FhirEngineConfiguration(
          storageDirectory = Files.createTempDirectory("fhir-engine-seconds-test").toString()
        )
      )
    }
    engine = FhirEngineProvider.getInstance()
    engine.clearDatabase()
  }

  private fun resource(json: String): Resource =
    FhirJson.instance.decodeFromString(Resource.serializer(), json)

  private fun Resource.json(): JsonObject =
    FhirJson.instance.encodeToJsonElement(Resource.serializer(), this).jsonObject

  private val consent =
    resource(
      """{"resourceType":"Consent","id":"share-1","status":"active",
        "meta":{"lastUpdated":"2026-11-14T14:56:23.611+00:00"},
        "dateTime":"2026-11-14T14:56:23.495871466Z",
        "scope":{"text":"patient-privacy"},"category":[{"text":"VHL"}]}"""
    )

  @Test
  fun aDownloadedDateTimeKeepsWholeSecondsOnly() {
    val trimmed = WholeSeconds.trim(consent).json()

    assertEquals("2026-11-14T14:56:23Z", trimmed.getValue("dateTime").jsonPrimitive.content)
    assertEquals(consent.json().getValue("meta"), trimmed.getValue("meta"))
  }

  @Test
  fun theEngineStoresWhatItCouldNotIndexBefore() = runTest {
    val repository = FhirEngineRepository(engine)

    assertFailsWith<ArithmeticException> { repository.storeAsSynced(listOf(consent)) }
    repository.storeAsSynced(listOf(WholeSeconds.trim(consent)))
  }
}
