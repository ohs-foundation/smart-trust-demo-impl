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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.sync.AcceptLocalConflictResolver
import dev.ohs.fhir.engine.sync.createDataStore
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.trustdemo.onesa.data.repository.FhirEngineRepository
import dev.ohs.trustdemo.onesa.data.sharing.InMemoryLinks
import dev.ohs.trustdemo.onesa.data.sharing.IssuedLink
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

class StoreOwnershipTest {
  private lateinit var engine: FhirEngine
  private lateinit var state: DataStore<Preferences>
  private val links = InMemoryLinks()
  private lateinit var outbox: Outbox
  private lateinit var ownership: StoreOwnership

  @BeforeTest
  fun setUp() = runTest {
    if (FhirEngineProvider.isNotInitialized()) {
      FhirEngineProvider.init(
        FhirEngineConfiguration(
          storageDirectory = Files.createTempDirectory("fhir-engine-ownership-test").toString()
        )
      )
    }
    engine = FhirEngineProvider.getInstance()
    engine.clearDatabase()
    val file = createTempFile(suffix = ".preferences_pb").toFile()
    file.deleteOnExit()
    state = createDataStore { file.absolutePath }
    val box = createTempFile(suffix = ".preferences_pb").toFile()
    box.deleteOnExit()
    outbox =
      Outbox(
        createDataStore { box.absolutePath },
        HttpClient(MockEngine { error("This test never sends") }),
        "http://gateway/fhir",
      ) {
        null
      }
    ownership = StoreOwnership(engine, state, links, outbox)
  }

  private fun patient(id: String) =
    FhirJson.instance.decodeFromString(
      Patient.serializer(),
      """{"resourceType":"Patient","id":"$id","active":true}""",
    )

  private suspend fun downloaded(id: String) =
    @Suppress("DEPRECATION")
    engine.syncDownload(AcceptLocalConflictResolver) { flowOf(listOf(patient(id))) }

  private suspend fun patients() = engine.count(Search(ResourceType.Patient))

  @Test
  fun aDifferentUserStartsWithAnEmptyStore() = runTest {
    assertIs<Admission.Allowed>(ownership.admit("alice"))
    downloaded("p1")
    DataStoreTimestampContext(state).saveLastUpdatedTimestamp(ResourceType.Patient, "2026-11-14")
    links.keep(listOf(IssuedLink("c1", "p1", "Alice", "HC1:one", exp = 9)))

    assertIs<Admission.Allowed>(ownership.admit("alice"))
    assertEquals(1, patients())

    assertIs<Admission.Allowed>(ownership.admit("bob"))
    assertEquals(0, patients())
    assertNull(DataStoreTimestampContext(state).getLasUpdateTimestamp(ResourceType.Patient))
    assertEquals(emptySet(), links.kept.keys)

    downloaded("p2")
    DataStoreInitialSyncStore(state).reset()
    assertIs<Admission.Allowed>(ownership.admit("bob"))
    assertEquals(1, patients())
  }

  @Test
  fun unsyncedWorkBlocksAnotherUsersSignIn() = runTest {
    assertIs<Admission.Allowed>(ownership.admit("alice"))
    FhirEngineRepository(engine).upsert(patient("p1"))

    val refused = ownership.admit("bob")

    assertIs<Admission.Blocked>(refused)
    assertEquals(1, patients())
    assertIs<Admission.Allowed>(ownership.admit("alice"))
    assertEquals(1, patients())
  }

  @Test
  fun anUnsentRegistrationBlocksAnotherUsersSignIn() = runTest {
    assertIs<Admission.Allowed>(ownership.admit("alice"))
    downloaded("p1")
    outbox.add(OutboxEntry("k1", JsonObject(emptyMap()), created = "Patient/p1"))

    assertIs<Admission.Blocked>(ownership.admit("bob"))
    assertEquals(1, patients())
  }
}
