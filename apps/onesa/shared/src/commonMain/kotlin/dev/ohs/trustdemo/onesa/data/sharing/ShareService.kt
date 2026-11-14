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
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A member of the household, as the synced Group and Patients name them. */
data class Person(val id: String, val name: String)

/** What asking the service for links came to. */
sealed interface ShareOutcome {
  /** One link per person is kept, under these Consent ids. */
  data class Issued(val consentIds: List<String>) : ShareOutcome

  /** The service said no; [message] is what it said. */
  data class Refused(val message: String) : ShareOutcome
}

/**
 * The family wallet's share: asks the Onesa VHL service for one signed link per person and keeps
 * each in [links]. The passcode goes to the service and nowhere else.
 */
class ShareService(
  private val repository: FhirRepository,
  private val accessToken: () -> String?,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val links: LinkStore,
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
  private val json = FhirJson.instance

  fun observeHousehold(selfId: String?): Flow<List<Person>> =
    repository.revision.map { household(selfId) }

  /** The people the synced Groups hold, [selfId] first. */
  suspend fun household(selfId: String?): List<Person> =
    repository
      .all("Group")
      .flatMap { group ->
        val members = group.toJson()["member"]?.jsonArray.orEmpty().map { it.jsonObject }
        members
          .filterNot { it["inactive"]?.jsonPrimitive?.contentOrNull == "true" }
          .mapNotNull { it["entity"]?.jsonObject?.get("reference")?.jsonPrimitive?.contentOrNull }
      }
      .filter { it.startsWith("Patient/") }
      .map { it.removePrefix("Patient/") }
      .distinct()
      .map { Person(it, nameOf(it)) }
      .sortedByDescending { it.id == selfId }

  suspend fun share(entry: ShareEntry): ShareOutcome {
    val token = accessToken() ?: error("Sign in again to share your records.")
    val start = today()
    if (entry.problem(start) != null) error("The share is not ready: ${entry.problem(start)}.")
    links.purgeExpired(start)
    val reply =
      httpClient.post("$baseUrl/vhl/\$generate-vhl") {
        bearerAuth(token)
        contentType(ContentType("application", "fhir+json"))
        setBody(ShareRequestBody.of(entry.people, start, entry.end, entry.passcode))
      }
    val text = reply.bodyAsText()
    if (!reply.status.isSuccess()) return ShareOutcome.Refused(refusal(text, reply.status.value))
    val issued = issuedLinks(text, entry.end.toString())
    links.keep(issued)
    return ShareOutcome.Issued(issued.map { it.consentId })
  }

  private suspend fun issuedLinks(text: String, expires: String): List<IssuedLink> =
    json
      .parseToJsonElement(text)
      .jsonObject["parameter"]
      ?.jsonArray
      .orEmpty()
      .map { it.jsonObject }
      .filter { it["name"]?.jsonPrimitive?.contentOrNull == "link" }
      .map { parameter ->
        val parts = parameter.getValue("part").jsonArray.map { it.jsonObject }

        fun reference(name: String) =
          parts
            .single { it["name"]?.jsonPrimitive?.contentOrNull == name }
            .getValue("valueReference")
            .jsonObject
            .getValue("reference")
            .jsonPrimitive
            .content
            .substringAfter('/')

        val person = reference("person")
        IssuedLink(
          consentId = reference("consent"),
          personId = person,
          name = nameOf(person),
          link =
            parts
              .single { it["name"]?.jsonPrimitive?.contentOrNull == "link" }
              .getValue("valueString")
              .jsonPrimitive
              .content,
          expires = expires,
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

  private suspend fun nameOf(patientId: String): String {
    val name =
      repository.get("Patient", patientId)?.toJson()?.get("name")?.jsonArray?.firstOrNull()
        as? JsonObject
    return name?.get("text")?.jsonPrimitive?.contentOrNull
      ?: listOfNotNull(
          name?.get("given")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
          name?.get("family")?.jsonPrimitive?.contentOrNull,
        )
        .joinToString(" ")
        .ifBlank { patientId }
  }

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject
}
