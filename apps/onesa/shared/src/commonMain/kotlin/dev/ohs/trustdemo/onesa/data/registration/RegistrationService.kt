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
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.sync.succeeded
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.onesa.util.idOf
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
  /** True when the registration is in but the local copy could not be refreshed. */
  val listStale: Boolean = false,
)

/** The household changed since this phone read it, so nothing was written. */
class HouseholdChangedException :
  IllegalStateException(
    "The household changed while you were registering. Nothing was saved; open the form again."
  )

/**
 * Registers a newborn into a household: loads the household and the parents the form names from the
 * local store, builds the transaction with [NewbornRegistration], and posts it to the gateway as
 * one request. The Group and the parents are written only at the version this phone read: if the
 * gateway holds a newer one, nothing is written. A [key] fixes the ids of one confirmed
 * registration, so a retry rewrites the records of the attempt before it.
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
  private val session: suspend () -> RegistrationSession?,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val refresh: suspend () -> Boolean,
  private val clock: () -> Instant = Clock.System::now,
) {
  private val json = FhirJson.instance

  suspend fun initialAnswers(groupId: String): Map<String, JsonObject> {
    val household = household(groupId)
    return NewbornRegistration.initialAnswers(
      household,
      scannedCards.cards.value,
      members(household),
    )
  }

  /** The household's members, whom the form offers as the child's parents. */
  suspend fun parentOptions(groupId: String): List<JsonObject> =
    NewbornRegistration.parentOptions(members(household(groupId)))

  /** The transaction [register] would send for [response]; it checks the same things first. */
  suspend fun preview(groupId: String, response: QuestionnaireResponse, key: String): JsonObject {
    val current = session() ?: error("Sign in again to register a newborn.")
    return NewbornRegistration.transaction(request(groupId, response, key, current))
  }

  suspend fun register(
    groupId: String,
    response: QuestionnaireResponse,
    key: String,
  ): RegistrationSummary {
    val current = session() ?: error("Sign in again to register a newborn.")
    val request = request(groupId, response, key, current)
    val cards = request.cards
    val transaction = NewbornRegistration.transaction(request)

    val reply =
      httpClient.post(baseUrl) {
        bearerAuth(current.accessToken)
        contentType(ContentType("application", "fhir+json"))
        setBody(json.encodeToString(JsonObject.serializer(), transaction))
      }
    if (
      reply.status == HttpStatusCode.PreconditionFailed || reply.status == HttpStatusCode.Conflict
    ) {
      succeeded(refresh)
      throw HouseholdChangedException()
    }
    check(reply.status.isSuccess()) {
      "The registration was not accepted (HTTP ${reply.status.value}): ${reply.bodyAsText().take(300)}"
    }

    scannedCards.clear()
    val synced = succeeded(refresh)
    val clinic =
      (repository.get("Organization", current.organizationId) as? Organization)?.name?.value
    return NewbornRegistration.summary(response, cards, clinic ?: current.organizationId)
      .copy(listStale = !synced)
  }

  private suspend fun request(
    groupId: String,
    response: QuestionnaireResponse,
    key: String,
    current: RegistrationSession,
  ): RegistrationRequest {
    val household = household(groupId)
    val (motherId, fatherId) = NewbornRegistration.parentIds(response)
    val members = members(household).associateBy { it.id }
    check(motherId != null || fatherId != null) {
      "Name at least one parent or guardian: the mother or the father."
    }
    val mother =
      motherId?.let { members[it] ?: error("The mother is not a member of this household.") }
    val father =
      fatherId?.let { members[it] ?: error("The father is not a member of this household.") }
    check(motherId != fatherId) { "The mother and the father are the same person." }
    check(listOfNotNull(household, mother, father).all { it.versionId() != null }) {
      "The household has not been downloaded to this phone yet. Sync, then try again."
    }
    return RegistrationRequest(
      response = response,
      household = household,
      mother = mother,
      father = father,
      cards = scannedCards.cards.value,
      practitionerId = current.practitionerId,
      organizationId = current.organizationId,
      now = clock(),
      newId = NewbornRegistration.idsFor(key),
    )
  }

  private suspend fun members(household: Group): List<Patient> =
    household.member
      .mapNotNull { it.entity.idOf("Patient") }
      .mapNotNull { repository.get("Patient", it) as? Patient }

  private fun Resource.versionId(): String? = meta?.versionId?.value

  private suspend fun household(groupId: String): Group =
    repository.get("Group", groupId) as? Group ?: error("Household $groupId was not found.")
}
