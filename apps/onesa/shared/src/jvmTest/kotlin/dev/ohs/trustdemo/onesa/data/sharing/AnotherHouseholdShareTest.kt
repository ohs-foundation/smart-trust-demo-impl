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
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.PendingAuth
import dev.ohs.trustdemo.onesa.auth.Session
import dev.ohs.trustdemo.onesa.auth.SessionStore
import dev.ohs.trustdemo.onesa.auth.UserContext
import dev.ohs.trustdemo.onesa.auth.UserInfo
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.feature.wallet.ShareViewModel
import dev.ohs.trustdemo.onesa.util.FhirJson
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** A household that is not the Sengmany family shares for the members its parent ticks. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnotherHouseholdShareTest {
  private val repository = InMemorySampleFhirRepository()
  private val sent = mutableListOf<List<String>>()

  private fun decode(json: String): Resource =
    FhirJson.instance.decodeFromString(Resource.serializer(), json)

  private fun patient(id: String, given: String, family: String, managedBy: String? = null) =
    decode(
      """{"resourceType":"Patient","id":"$id","name":[{"family":"$family","given":["$given"]}]${
        managedBy?.let { ""","link":[{"other":{"reference":"RelatedPerson/$it"},"type":"seealso"}]""" }.orEmpty()
      }}"""
    )

  private fun household(id: String, manager: String, vararg members: String) =
    decode(
      """{"resourceType":"Group","id":"$id","type":"person","actual":true,
        "managingEntity":{"reference":"RelatedPerson/$manager"},
        "member":[${members.joinToString(",") { """{"entity":{"reference":"Patient/$it"}}""" }}]}"""
    )

  private val service =
    ShareService(
      repository = repository,
      accessToken = { "token" },
      httpClient =
        HttpClient(
          MockEngine { request ->
            val people = ShareRequest.parse((request.body as TextContent).text).people
            sent += people
            respond(
              replyFor(people),
              HttpStatusCode.OK,
              headersOf(HttpHeaders.ContentType, "application/fhir+json"),
            )
          }
        ),
      baseUrl = "https://vhl.test:8443",
      links = InMemoryLinks(),
      fhirBaseUrl = "https://gateway.test/fhir/",
      expiry = { null },
      now = { 0 },
    )

  @Test
  fun theManagingParentSharesForTwoOfTheirHousehold() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      listOf(
          patient("noy", "Noy", "VONGSA", managedBy = "rp-noy"),
          patient("kham", "Kham", "VONGSA"),
          patient("lattana", "Lattana", "VONGSA"),
          household("vongsa", "rp-noy", "kham", "lattana", "noy"),
          patient("bounmy", "Bounmy", "SENGMANY", managedBy = "rp-bounmy"),
          patient("somsak", "Somsak", "SENGMANY"),
          household("sengmany", "rp-bounmy", "bounmy", "somsak"),
        )
        .forEach { repository.upsert(it) }
      val viewModel = ShareViewModel(service, SignedIn("noy"))
      val loaded = viewModel.state.first { it.members.isNotEmpty() }

      assertEquals(listOf("noy", "kham", "lattana"), loaded.members.map { it.id })
      assertEquals(setOf("noy", "kham", "lattana"), loaded.selected)

      listOf("noy", "kham", "lattana").forEach(viewModel::toggle)
      viewModel.passcode("0482")
      viewModel.again("0482")
      viewModel.agree(true)
      viewModel.share()
      assertEquals(ShareProblem.NoOne, viewModel.state.value.problem)
      assertEquals(emptyList(), sent)

      viewModel.toggle("noy")
      viewModel.toggle("lattana")
      viewModel.share()
      val issued = viewModel.state.first { it.issued != null || it.refusal != null }

      assertEquals(listOf(listOf("noy", "lattana")), sent)
      assertEquals(listOf("c-noy", "c-lattana"), issued.issued)
    } finally {
      Dispatchers.resetMain()
    }
  }
}

private class SignedIn(patientId: String) : SessionStore {
  override val session: StateFlow<Session?> =
    MutableStateFlow(
      Session(
        accessToken = "token",
        refreshToken = null,
        idToken = null,
        expiresInSeconds = 300,
        obtainedAtEpochSeconds = 0,
        user = UserInfo(fhirUser = "Patient/$patientId"),
        context = UserContext(AppRole.FAMILY, patientId = patientId),
      )
    )

  override suspend fun load(): Session? = session.value

  override suspend fun save(session: Session) = Unit

  override suspend fun clear() = Unit

  override suspend fun savePending(pending: PendingAuth) = Unit

  override suspend fun takePending(): PendingAuth? = null
}
