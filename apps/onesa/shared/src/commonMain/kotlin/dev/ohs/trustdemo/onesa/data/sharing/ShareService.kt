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
import dev.ohs.trustdemo.hcert.ReadResult
import dev.ohs.trustdemo.hcert.loadHcertEngine
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.sync.IndexableCopy
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val REPLY_TIMEOUT_MILLIS = 60_000L
private const val CONSENT_PAGE = 100

/** A member of the household; [name] is null until the person's Patient has synced. */
data class Person(val id: String, val name: String?)

/** What asking the service for links came to. */
sealed interface ShareOutcome {
  /** One link per person; [saved] is false when this phone could not keep them. */
  data class Issued(val links: List<IssuedLink>, val saved: Boolean) : ShareOutcome {
    val consentIds: List<String>
      get() = links.map { it.consentId }
  }

  /** The service said no; [message] is what it said. */
  data class Refused(val message: String) : ShareOutcome

  /** The share may exist but its reply was not read; asking again with the same key is safe. */
  data class Unknown(val message: String) : ShareOutcome
}

/**
 * The family wallet's share: asks the Onesa VHL service for one signed link per person and keeps
 * each in [links]. The passcode is kept with them and sent to the service only. A share carries an
 * Idempotency-Key, so a lost reply is asked for again and never creates a second set of Consents.
 * The share's Consents are read back from the gateway at [fhirBaseUrl] and kept as server copies,
 * so Shared Links lists them at once and the sync never uploads them. [expiry] reads when a link
 * stops working; [now] is the clock in epoch seconds.
 */
class ShareService(
  private val repository: FhirRepository,
  private val accessToken: suspend () -> String?,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val links: LinkStore,
  private val fhirBaseUrl: String,
  private val expiry: suspend (String) -> Long? = ::expiryOf,
  private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
  private val json = FhirJson.instance
  private val unsaved = mutableMapOf<String, IssuedLink>()

  private val records = FamilyRecords(repository)

  fun observeHousehold(selfId: String?): Flow<List<Person>> =
    repository.revision.map { household(selfId) }

  /**
   * The people of [selfId]'s households, [selfId] first: the synced Groups managed by a related
   * person of theirs, or that they belong to. A phone another family used may hold other Groups.
   */
  suspend fun household(selfId: String?): List<Person> {
    if (selfId == null) return emptyList()
    val related = relatedPersonsOf(selfId)
    return repository
      .all("Group")
      .map { it.toJson() }
      .filter { group ->
        group.reference("managingEntity") in related || selfId in group.memberIds()
      }
      .flatMap { it.memberIds() }
      .distinct()
      .map { Person(it, nameOf(it)) }
      .sortedByDescending { it.id == selfId }
  }

  private suspend fun relatedPersonsOf(selfId: String): Set<String> =
    repository
      .get("Patient", selfId)
      ?.toJson()
      ?.get("link")
      ?.jsonArray
      .orEmpty()
      .mapNotNull { it.jsonObject["other"]?.jsonObject?.reference() }
      .filter { it.startsWith("RelatedPerson/") }
      .toSet()

  private fun JsonObject.memberIds(): List<String> =
    this["member"]
      ?.jsonArray
      .orEmpty()
      .map { it.jsonObject }
      .filterNot { it["inactive"]?.jsonPrimitive?.contentOrNull == "true" }
      .mapNotNull { it["entity"]?.jsonObject?.reference() }
      .filter { it.startsWith("Patient/") }
      .map { it.removePrefix("Patient/") }

  private fun JsonObject.reference(name: String): String? = this[name]?.jsonObject?.reference()

  private fun JsonObject.reference(): String? = this["reference"]?.jsonPrimitive?.contentOrNull

  suspend fun purgeExpired() = links.purgeExpired(now())

  /** [people] with each one's relation, head and birth date, from the family's records. */
  suspend fun family(people: List<Person>): List<FamilyMember> = records.members(people)

  /** [patientId]'s doses, oldest first. */
  suspend fun doses(patientId: String): List<FamilyDose> = records.doses(patientId)

  /** [patientId]'s own problems, medicines, allergies and vital signs. */
  suspend fun health(patientId: String): FamilyHealth = records.health(patientId)

  /** The links of [consentIds], and whether any of them is held in memory only. */
  suspend fun issued(consentIds: List<String>): Pair<List<IssuedLink>, Boolean> {
    val kept = links.get(consentIds)
    val loose =
      consentIds.filter { id -> kept.none { it.consentId == id } }.mapNotNull(unsaved::get)
    return (kept + loose) to loose.isNotEmpty()
  }

  /** Shares from [start]; the same [key] for the same [entry] returns the same links. */
  suspend fun share(entry: ShareEntry, start: LocalDate, key: String): ShareOutcome {
    val token = accessToken() ?: error("Sign in again to share your records.")
    if (entry.problem(start) != null) {
      return ShareOutcome.Refused("Check the people, the last day and the passcode.")
    }
    val body = ShareRequestBody.of(entry.people, start, entry.end, entry.passcode)
    val reply =
      try {
        post(token, body, key)
      } catch (e: IOException) {
        return ShareOutcome.Unknown(LOST)
      }
    val text = reply.bodyAsText()
    if (!reply.status.isSuccess()) return ShareOutcome.Refused(refusal(text, reply.status.value))
    val issued =
      try {
        issuedLinks(text, entry)
      } catch (e: SerializationException) {
        null
      } catch (e: NoSuchElementException) {
        null
      } catch (e: IllegalArgumentException) {
        null
      }
    if (issued == null || issued.map { it.personId } != entry.people) {
      return ShareOutcome.Unknown(UNREADABLE)
    }
    val saved =
      try {
        links.keep(issued)
        true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        issued.forEach { unsaved[it.consentId] = it }
        false
      }
    keepConsents(token, issued.map { it.consentId })
    return ShareOutcome.Issued(issued, saved)
  }

  /**
   * Reads the family's Consents as the sync does and keeps the ones of [ids] as server copies; when
   * that fails the next sync brings them.
   */
  private suspend fun keepConsents(token: String, ids: List<String>) {
    try {
      val reply =
        httpClient.get("${fhirBaseUrl}Consent") {
          bearerAuth(token)
          parameter("_count", CONSENT_PAGE)
        }
      if (!reply.status.isSuccess()) return
      val consents =
        json
          .parseToJsonElement(reply.bodyAsText())
          .jsonObject["entry"]
          ?.jsonArray
          .orEmpty()
          .mapNotNull { it.jsonObject["resource"] }
          .map { json.decodeFromJsonElement(Resource.serializer(), it) }
          .filter { it.id in ids }
          .map(IndexableCopy::of)
      if (consents.isNotEmpty()) repository.storeAsSynced(consents)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {}
  }

  private suspend fun post(token: String, body: String, key: String): HttpResponse {
    suspend fun attempt() =
      httpClient.post("$baseUrl/vhl/\$generate-vhl") {
        bearerAuth(token)
        header("Idempotency-Key", key)
        contentType(ContentType("application", "fhir+json"))
        timeout { requestTimeoutMillis = REPLY_TIMEOUT_MILLIS }
        setBody(body)
      }
    return try {
      attempt()
    } catch (e: IOException) {
      attempt()
    }
  }

  private suspend fun issuedLinks(text: String, entry: ShareEntry): List<IssuedLink> =
    json
      .parseToJsonElement(text)
      .jsonObject["parameter"]
      ?.jsonArray
      .orEmpty()
      .map { it.jsonObject }
      .filter { it["name"]?.jsonPrimitive?.contentOrNull == "link" }
      .map { parameter ->
        val parts = parameter.getValue("part").jsonArray.map { it.jsonObject }

        fun part(name: String) = parts.single { it["name"]?.jsonPrimitive?.contentOrNull == name }

        fun reference(name: String) =
          part(name)
            .getValue("valueReference")
            .jsonObject
            .getValue("reference")
            .jsonPrimitive
            .content
            .substringAfter('/')

        val person = reference("person")
        val hc1 = part("link").getValue("valueString").jsonPrimitive.content
        IssuedLink(
          consentId = reference("consent"),
          personId = person,
          name = nameOf(person) ?: person,
          link = hc1,
          exp =
            expiry(hc1)
              ?: entry.end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).epochSeconds,
          passcode = entry.passcode,
        )
      }

  private fun refusal(text: String, status: Int): String =
    try {
      json
        .parseToJsonElement(text)
        .jsonObject["issue"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("diagnostics")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    } catch (_: SerializationException) {
      null
    } ?: "The service refused the share (HTTP $status)."

  suspend fun nameOf(patientId: String): String? {
    val patient = repository.get("Patient", patientId)?.toJson() ?: return null
    val name = patient["name"]?.jsonArray?.firstOrNull() as? JsonObject
    return name?.get("text")?.jsonPrimitive?.contentOrNull
      ?: listOfNotNull(
          name?.get("given")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
          name?.get("family")?.jsonPrimitive?.contentOrNull,
        )
        .joinToString(" ")
        .ifBlank { "Unnamed" }
  }

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject

  private companion object {
    const val LOST = "The share may have been created; try again."
    const val UNREADABLE =
      "The reply could not be read. The share may have been created; try again."
  }
}

private suspend fun expiryOf(hc1: String): Long? =
  (loadHcertEngine().readUnverified(hc1) as? ReadResult.Decoded)?.hcert?.vhl?.exp
