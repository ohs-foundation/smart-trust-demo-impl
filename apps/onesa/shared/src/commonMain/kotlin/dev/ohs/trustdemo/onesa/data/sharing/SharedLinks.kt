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
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private const val CONSENT_POLICY = "CONSENT-VHL-TREATMENT"
private const val ACCESS_PROFILE =
  "http://smart.who.int/trust-demo/StructureDefinition/TrustDemoOnesaVhlAccess"
private const val DOCUMENT_INTERACTION = "ITI-68"
private const val REPLY_TIMEOUT_MILLIS = 60_000L

/** Where a share stands: withdrawn by the family, past its last day, or still open. */
enum class ShareStatus {
  Active,
  Withdrawn,
  Expired,
}

/** Why the service refused a clinic, by the code its audit records. */
enum class Refusal(val code: String?) {
  Withdrawn("withdrawn"),
  Expired("expired"),
  WrongPasscode("wrong-passcode"),
  AttemptsExhausted("attempts-exhausted"),
  Other(null);

  companion object {
    fun of(code: String?): Refusal = entries.firstOrNull { it.code == code } ?: Other
  }
}

/** One request a clinic made of a share: what it asked for and whether the service released it. */
data class Look(
  val id: String,
  val at: Instant,
  val clinic: String?,
  val document: Boolean,
  val released: Boolean,
  val refusal: Refusal?,
)

/** A share the household made, with the requests made of it, newest first. */
data class SharedLink(
  val consentId: String,
  val personId: String,
  val name: String?,
  val end: LocalDate,
  val status: ShareStatus,
  /** True when this phone still holds the share's link, so its QR can be shown again. */
  val kept: Boolean,
  val looks: List<Look>,
)

/** What asking the service to withdraw a share came to. */
sealed interface WithdrawOutcome {
  data object Withdrawn : WithdrawOutcome

  /** The share changed while the request was made; asking again is safe. */
  data object Changed : WithdrawOutcome

  data object NotYours : WithdrawOutcome

  data object Gone : WithdrawOutcome

  data class Failed(val message: String) : WithdrawOutcome
}

/**
 * The household's shares as the synced Consents and access AuditEvents describe them, and the
 * withdrawal of a share. An access is tied to its share by the Consent its audit names. [refresh]
 * pulls the server's state after a withdrawal.
 */
class SharedLinks(
  private val repository: FhirRepository,
  private val accessToken: () -> String?,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val links: LinkStore,
  private val names: suspend (String) -> String?,
  private val refresh: suspend () -> Unit,
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.UTC) },
) {
  private val json = FhirJson.instance
  private val withdrawn = mutableSetOf<String>()

  fun observe(): Flow<List<SharedLink>> = repository.revision.map { list() }

  suspend fun list(): List<SharedLink> {
    val consents =
      repository
        .all("Consent")
        .map { it.toJson() }
        .filter { CONSENT_POLICY in it["policyRule"].codes() }
    val kept = links.get(consents.mapNotNull { it["id"]?.text() }).map { it.consentId }.toSet()
    val looks =
      repository
        .all("AuditEvent")
        .mapNotNull { it.toJson().look() }
        .groupBy({ it.first }, { it.second })
    return consents
      .mapNotNull { consent ->
        val id = consent["id"]?.text() ?: return@mapNotNull null
        val person =
          consent.reference("patient")?.removePrefix("Patient/") ?: return@mapNotNull null
        val period = consent["provision"]?.jsonObject?.get("period")?.jsonObject
        val end = LocalDate.parse(period?.get("end")?.text()?.take(10) ?: return@mapNotNull null)
        SharedLink(
          consentId = id,
          personId = person,
          name = names(person),
          end = end,
          status = statusOf(consent, id, end),
          kept = id in kept,
          looks = looks[id].orEmpty().sortedByDescending { it.at },
        )
      }
      .sortedBy { it.end }
  }

  /**
   * Asks the service to withdraw [consentId]; on success only that share's kept link is dropped.
   */
  suspend fun withdraw(consentId: String): WithdrawOutcome {
    val token = accessToken() ?: return WithdrawOutcome.Failed("Sign in again to withdraw a share.")
    val reply =
      try {
        httpClient.post("$baseUrl/vhl/\$withdraw") {
          bearerAuth(token)
          contentType(ContentType("application", "fhir+json"))
          timeout { requestTimeoutMillis = REPLY_TIMEOUT_MILLIS }
          setBody(withdrawBody(consentId))
        }
      } catch (e: IOException) {
        return WithdrawOutcome.Failed("The service could not be reached; try again.")
      }
    return when (reply.status) {
      HttpStatusCode.OK -> {
        withdrawn += consentId
        links.remove(consentId)
        refresh()
        WithdrawOutcome.Withdrawn
      }
      HttpStatusCode.Conflict -> {
        refresh()
        WithdrawOutcome.Changed
      }
      HttpStatusCode.Forbidden -> WithdrawOutcome.NotYours
      HttpStatusCode.NotFound -> WithdrawOutcome.Gone
      else -> WithdrawOutcome.Failed("The service refused (HTTP ${reply.status.value}).")
    }
  }

  private fun statusOf(consent: JsonObject, id: String, end: LocalDate) =
    when {
      consent["status"]?.text() == "inactive" || id in withdrawn -> ShareStatus.Withdrawn
      end < today() -> ShareStatus.Expired
      else -> ShareStatus.Active
    }

  /** The Consent an access audit names and the access, or null for any other AuditEvent. */
  private fun JsonObject.look(): Pair<String, Look>? {
    val profiles =
      this["meta"]?.jsonObject?.get("profile")?.jsonArray.orEmpty().mapNotNull { it.text() }
    if (ACCESS_PROFILE !in profiles) return null
    val consent =
      this["entity"]?.jsonArray.orEmpty().firstNotNullOfOrNull {
        it.jsonObject["what"]?.jsonObject?.get("reference")?.text()?.takeIf { ref ->
          ref.startsWith("Consent/")
        }
      } ?: return null
    val subtypes = this["subtype"]?.jsonArray.orEmpty().map { it.jsonObject }
    val released = this["outcome"]?.text() == "0"
    val clinic =
      this["agent"]
        ?.jsonArray
        .orEmpty()
        .map { it.jsonObject }
        .firstOrNull { it["requestor"]?.text() == "true" }
    return consent.removePrefix("Consent/") to
      Look(
        id = this["id"]?.text() ?: return null,
        at = Instant.parse(this["recorded"]?.text() ?: return null),
        clinic =
          clinic?.get("name")?.text() ?: clinic?.get("who")?.jsonObject?.get("display")?.text(),
        document = subtypes.any { it["display"]?.text()?.contains(DOCUMENT_INTERACTION) == true },
        released = released,
        refusal =
          if (released) null else Refusal.of(subtypes.firstNotNullOfOrNull { it["code"]?.text() }),
      )
  }

  private fun JsonObject.reference(name: String): String? =
    this[name]?.jsonObject?.get("reference")?.text()

  private fun JsonElement?.codes(): List<String> =
    (this as? JsonObject)?.get("coding")?.jsonArray.orEmpty().mapNotNull {
      it.jsonObject["code"]?.text()
    }

  private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject

  private fun withdrawBody(consentId: String) =
    """{"resourceType":"Parameters","parameter":[{"name":"consent","valueReference":{"reference":"Consent/$consentId"}}]}"""
}
