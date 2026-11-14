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

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class HouseholdListTest {
  private val repository = InMemorySampleFhirRepository()
  private val service =
    ShareService(
      repository = repository,
      accessToken = { "token" },
      httpClient = HttpClient(MockEngine { respond("{}") }),
      baseUrl = "https://vhl.test:8443",
      links = InMemoryLinks(),
    )

  private suspend fun load() {
    listOf(
        """{"resourceType":"Patient","id":"bounmy","link":[{"other":{"reference":"RelatedPerson/bounmy-rp"},"type":"seealso"}]}""",
        group("house-1", "bounmy-rp", "bounmy", "khamla", "somsak"),
        group("house-2", "other-rp", "p7", "p8"),
      )
      .forEach { repository.upsert(FhirJson.instance.decodeFromString(Resource.serializer(), it)) }
  }

  private fun group(id: String, managedBy: String, vararg members: String) =
    """{"resourceType":"Group","id":"$id","type":"person","actual":true,
      "managingEntity":{"reference":"RelatedPerson/$managedBy"},
      "member":[${members.joinToString(",") { """{"entity":{"reference":"Patient/$it"}}""" }}]}"""

  @Test
  fun aFamilyHouseholdListKeepsOnlyItsOwnHouseholds() = runTest {
    load()

    assertEquals(listOf("bounmy", "khamla", "somsak"), service.household("bounmy").map { it.id })
    assertEquals(listOf("khamla", "bounmy", "somsak"), service.household("khamla").map { it.id })
    assertEquals(listOf("p7", "p8"), service.household("p7").map { it.id }.sorted())
    assertEquals(emptyList(), service.household("nobody"))
    assertEquals(emptyList(), service.household(null))
  }
}
