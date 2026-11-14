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
package dev.ohs.trustdemo.onesa.feature.role

import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.PendingAuth
import dev.ohs.trustdemo.onesa.auth.PractitionerDetailsApi
import dev.ohs.trustdemo.onesa.auth.Session
import dev.ohs.trustdemo.onesa.auth.SessionStore
import dev.ohs.trustdemo.onesa.auth.UserContext
import dev.ohs.trustdemo.onesa.auth.UserInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

private class FakeSessionStore(initial: Session?) : SessionStore {
  private val _session = MutableStateFlow(initial)
  override val session: StateFlow<Session?> = _session

  override suspend fun load(): Session? = _session.value

  override suspend fun save(session: Session) {
    _session.value = session
  }

  override suspend fun clear() {
    _session.value = null
  }

  override suspend fun savePending(pending: PendingAuth) = Unit

  override suspend fun takePending(): PendingAuth? = null
}

class UserContextViewModelTest {
  private val requests = mutableListOf<String?>()

  private fun session(fhirUser: String?) =
    Session(
      accessToken = "token-1",
      refreshToken = null,
      idToken = null,
      expiresInSeconds = 300,
      obtainedAtEpochSeconds = 0,
      user = UserInfo(fhirUser = fhirUser),
    )

  private fun viewModelFor(session: Session, status: HttpStatusCode, body: String = "") =
    UserContextViewModel(
      PractitionerDetailsApi(
        baseUrl = "https://gateway.example.org/",
        httpClient =
          HttpClient(
            MockEngine { request ->
              requests += request.headers[HttpHeaders.Authorization]
              respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
          ),
      )::fetch,
      FakeSessionStore(session),
      resetSync = {},
      freshSession = { session },
    )

  @Test
  fun familyLoginResolvesToFamilyRole() = runTest {
    val viewModel =
      viewModelFor(session("Patient/TrustDemoBounmyPatient"), HttpStatusCode.InternalServerError)

    viewModel.resolve().join()

    assertEquals(
      UserContextState.Ready(UserContext(AppRole.FAMILY, patientId = "TrustDemoBounmyPatient")),
      viewModel.state.value,
    )
    assertEquals(emptyList(), requests)
  }

  @Test
  fun staffLoginResolvesFromPractitionerRoles() = runTest {
    val viewModel =
      viewModelFor(session("Practitioner/TrustDemoVilay"), HttpStatusCode.OK, STAFF_DETAILS)

    viewModel.resolve().join()

    assertEquals(
      UserContextState.Ready(
        UserContext(AppRole.CHW, "TrustDemoVilay", "TrustDemoLomaruDhc", "l1")
      ),
      viewModel.state.value,
    )
    assertEquals(listOf<String?>("Bearer token-1"), requests)
  }

  @Test
  fun noRoleWhenNeither() = runTest {
    val unknown = viewModelFor(session("Organization/o1"), HttpStatusCode.OK, STAFF_DETAILS)
    val noPractitioner = viewModelFor(session(null), HttpStatusCode.NotFound)

    unknown.resolve().join()
    noPractitioner.resolve().join()

    assertEquals(UserContextState.NoRole, unknown.state.value)
    assertEquals(UserContextState.NoRole, noPractitioner.state.value)
    assertEquals(listOf<String?>("Bearer token-1"), requests)
  }

  private companion object {
    const val STAFF_DETAILS =
      """
      {
        "practitioner": { "resourceType": "Practitioner", "id": "TrustDemoVilay" },
        "practitionerRoles": [
          {
            "practitionerRole": {
              "resourceType": "PractitionerRole",
              "id": "TrustDemoVilayRole",
              "code": [ { "coding": [ { "system": "http://ohs.dev/fhir/CodeSystem/app-role", "code": "chw" } ] } ],
              "organization": { "reference": "Organization/TrustDemoLomaruDhc" },
              "location": [ { "reference": "Location/l1" } ]
            },
            "organization": { "resourceType": "Organization", "id": "TrustDemoLomaruDhc" }
          }
        ]
      }
      """
  }
}
