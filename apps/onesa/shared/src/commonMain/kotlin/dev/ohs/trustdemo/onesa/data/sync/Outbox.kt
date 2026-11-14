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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val OUTBOX_DATASTORE_FILE_NAME = "outbox.preferences_pb"

/**
 * Its own DataStore file, apart from the sync timestamps: a new sign-in clears those, but work
 * saved on the phone stays until it uploads.
 */
internal expect fun createOutboxDataStore(): DataStore<Preferences>

/** Why the server refused an entry: its HTTP [status] and the [reason] it gave. */
@Serializable data class Refusal(val status: Int, val reason: String)

/**
 * A transaction saved on this phone, posted as one request. Finding the [created] record on the
 * server shows an earlier post went in although its reply was lost. A [refused] entry is not sent
 * again.
 */
@Serializable
data class OutboxEntry(
  val key: String,
  val transaction: JsonObject,
  val created: String,
  val refused: Refusal? = null,
)

/**
 * Transactions saved on this phone that wait to upload: the offline-first outbox. Each is posted as
 * one request, oldest first, before the engine's own sync. A post that cannot reach the server ends
 * the round and keeps the rest; one the server refuses is kept with its reason.
 */
class Outbox(
  private val dataStore: DataStore<Preferences>,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val accessToken: suspend () -> String?,
) {
  private val json = FhirJson.instance
  private val sending = Mutex()

  suspend fun entries(): List<OutboxEntry> =
    dataStore.data.first()[ENTRIES]?.let { json.decodeFromString(LIST, it) }.orEmpty()

  /** Keeps [entry], in the place of one saved earlier under its key. */
  suspend fun add(entry: OutboxEntry) = edit { entries ->
    if (entries.none { it.key == entry.key }) entries + entry
    else entries.map { if (it.key == entry.key) entry else it }
  }

  suspend fun remove(key: String) = edit { entries -> entries.filterNot { it.key == key } }

  /** Posts every entry the server has not refused, oldest first. */
  suspend fun send() {
    sending.withLock {
      for (entry in entries().filter { it.refused == null }) {
        try {
          val token = accessToken() ?: return
          val reply =
            httpClient.post(baseUrl) {
              bearerAuth(token)
              contentType(ContentType("application", "fhir+json"))
              setBody(json.encodeToString(JsonObject.serializer(), entry.transaction))
            }
          when {
            reply.status.isSuccess() || reply.alreadyIn(entry, token) -> remove(entry.key)
            reply.status.value in 400..499 && reply.status !in TRANSIENT -> {
              val refused = refusal(reply)
              edit { entries ->
                entries.map { if (it.key == entry.key) it.copy(refused = refused) else it }
              }
            }
            else -> return
          }
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return
        }
      }
    }
  }

  /** A version conflict on a post whose earlier attempt went in, its reply lost on the way. */
  private suspend fun HttpResponse.alreadyIn(entry: OutboxEntry, token: String): Boolean =
    (status == HttpStatusCode.PreconditionFailed || status == HttpStatusCode.Conflict) &&
      httpClient
        .get("${baseUrl.removeSuffix("/")}/${entry.created}") { bearerAuth(token) }
        .status
        .isSuccess()

  private suspend fun refusal(reply: HttpResponse): Refusal {
    val text = reply.bodyAsText()
    val diagnostics =
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
      }
    return Refusal(reply.status.value, diagnostics ?: text.take(300))
  }

  private suspend fun edit(change: (List<OutboxEntry>) -> List<OutboxEntry>) {
    dataStore.edit { prefs ->
      val entries = prefs[ENTRIES]?.let { json.decodeFromString(LIST, it) }.orEmpty()
      prefs[ENTRIES] = json.encodeToString(LIST, change(entries))
    }
  }

  private companion object {
    val ENTRIES = stringPreferencesKey("outbox_entries")
    val LIST = ListSerializer(OutboxEntry.serializer())

    /** Not the server judging the records: an expired session, a timeout, a rate limit. */
    val TRANSIENT =
      setOf(
        HttpStatusCode.Unauthorized,
        HttpStatusCode.RequestTimeout,
        HttpStatusCode.TooManyRequests,
      )
  }
}
