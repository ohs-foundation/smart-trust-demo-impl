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
package dev.ohs.trustdemo.onesa.data.registration

import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.onesa.util.idOf
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject

/** What the signed-in CHW's session lends to a registration. */
data class RegistrationSession(
  val accessToken: String,
  val practitionerId: String,
  val organizationId: String,
)

/** What the CHW sees once the registration is in: the child, the doses noted, and who is told. */
data class RegistrationSummary(
  val childName: String,
  val birthDate: String,
  val doses: List<String>,
  val referredTo: String?,
)

/**
 * Registers a newborn into a household: loads the household and its parents from the local store,
 * builds the transaction with [NewbornRegistration], and posts it to the gateway as one request.
 *
 * The transaction goes straight to the gateway rather than through the engine's sync upload. The
 * engine uploads each local change as its own `PUT` or `PATCH` and cannot send the household's
 * `member` list as one replacement, nor guarantee that the child, the doses and the referral arrive
 * in one request; the gateway's write rules judge the Group update by the whole Bundle. The cost is
 * that registering needs a connection, where scanning cards does not.
 */
class RegistrationService(
  private val repository: FhirRepository,
  private val scannedCards: ScannedCards,
  private val session: () -> RegistrationSession?,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val refresh: suspend () -> Unit,
  private val clock: () -> Instant = Clock.System::now,
) {
  private val json = FhirJson.instance

  suspend fun initialAnswers(groupId: String): Map<String, JsonObject> =
    NewbornRegistration.initialAnswers(household(groupId), scannedCards.cards.value)

  suspend fun register(groupId: String, response: QuestionnaireResponse): RegistrationSummary {
    val current = session() ?: error("Sign in again to register a newborn.")
    val household = household(groupId)
    val parents =
      household.member
        .mapNotNull { it.entity.idOf("Patient") }
        .mapNotNull { repository.get("Patient", it) as? Patient }
    val cards = scannedCards.cards.value
    val transaction =
      NewbornRegistration.transaction(
        RegistrationRequest(
          response = response,
          household = household,
          parents = parents,
          cards = cards,
          practitionerId = current.practitionerId,
          organizationId = current.organizationId,
          now = clock(),
        )
      )

    val reply =
      httpClient.post(baseUrl) {
        bearerAuth(current.accessToken)
        contentType(ContentType("application", "fhir+json"))
        setBody(json.encodeToString(JsonObject.serializer(), transaction))
      }
    check(reply.status.isSuccess()) {
      "The registration was not accepted (HTTP ${reply.status.value}): ${reply.bodyAsText().take(300)}"
    }

    scannedCards.clear()
    refresh()
    val clinic =
      (repository.get("Organization", current.organizationId) as? Organization)?.name?.value
    return NewbornRegistration.summary(response, cards, clinic ?: current.organizationId)
  }

  private suspend fun household(groupId: String): Group =
    repository.get("Group", groupId) as? Group ?: error("Household $groupId was not found.")
}
