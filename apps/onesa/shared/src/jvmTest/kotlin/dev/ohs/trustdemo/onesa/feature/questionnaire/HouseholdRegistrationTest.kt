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
package dev.ohs.trustdemo.onesa.feature.questionnaire

import dev.ohs.fhir.engine.sync.createDataStore
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.trustdemo.onesa.data.registration.RegistrationService
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.registration.ScannedCards
import dev.ohs.trustdemo.onesa.data.repository.GroupRepository
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.sync.Outbox
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HouseholdRegistrationTest {
  private val repository = InMemorySampleFhirRepository()

  private fun service(session: RegistrationSession?) =
    QuestionnaireService(
      repository = repository,
      registration =
        RegistrationService(
          repository = repository,
          scannedCards = ScannedCards(),
          session = { session },
          outbox =
            Outbox(
              dataStore =
                createDataStore {
                  createTempFile(suffix = ".preferences_pb")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
                },
              httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
              baseUrl = "http://gateway/fhir",
              accessToken = { "token" },
            ),
          refresh = { true },
        ),
      groups = GroupRepository(repository),
      session = { session },
    )

  private fun text(linkId: String, value: String) =
    """{"linkId":"$linkId","answer":[{"valueString":"$value"}]}"""

  private val response =
    FhirJson.instance.decodeFromString(
      QuestionnaireResponse.serializer(),
      """{"resourceType":"QuestionnaireResponse","status":"completed","item":[
        {"linkId":"page-household","item":[{"linkId":"household-details","item":[
          ${text("household-id", "HH-9")},${text("household-name", "Sengmany")}]}]},
        {"linkId":"page-household-head","item":[{"linkId":"household-head","item":[
          ${text("head-given-name", "Bounmy")},${text("head-family-name", "Sengmany")}]}]},
        {"linkId":"page-household-members","item":[{"linkId":"household-members","item":[
          ${text("member-given-name", "Khamla")},${text("member-family-name", "Sengmany")}]}]}
      ]}""",
    )

  private suspend fun submit(session: RegistrationSession?): JsonObject {
    val service = service(session)
    val questionnaire = service.getQuestionnaire(QuestionnaireIds.HOUSEHOLD_REGISTRATION)
    val result = service.submit(questionnaire, response, QuestionnaireLaunchContext())
    return Json.parseToJsonElement(result.bundleJson).jsonObject
  }

  @Test
  fun everyRegisteredPersonBelongsToTheSignedInOrganisation() = runTest {
    val bundle = submit(RegistrationSession("token", "vilay", "lomaru-dhc"))

    val patients =
      bundle["entry"]!!
        .jsonArray
        .map { it.jsonObject["resource"]!!.jsonObject }
        .filter { it["resourceType"]!!.jsonPrimitive.content == "Patient" }
    assertTrue(patients.isNotEmpty())
    patients.forEach {
      assertEquals(
        "Organization/lomaru-dhc",
        it["managingOrganization"]!!.jsonObject["reference"]!!.jsonPrimitive.content,
      )
    }
  }

  @Test
  fun registeringAHouseholdNeedsASession() = runTest {
    val failure = assertFailsWith<IllegalStateException> { submit(null) }

    assertEquals("Sign in again to register a household.", failure.message)
  }
}
