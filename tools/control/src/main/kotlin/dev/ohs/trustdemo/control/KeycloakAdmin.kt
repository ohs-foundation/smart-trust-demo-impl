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
package dev.ohs.trustdemo.control

import com.github.ajalt.clikt.core.CliktError
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The part of Keycloak's admin API the CLI uses, as its own service account (a client of the demo
 * realm that holds manage-users and view-users), so no master-realm credential is kept.
 */
class KeycloakAdmin(
  private val baseUrl: String,
  private val realm: String,
  private val clientId: String,
  private val clientSecret: String,
  engine: HttpClientEngine,
) {
  private val client = HttpClient(engine)
  private var token: String? = null

  suspend fun usernameTaken(username: String): Boolean =
    users("username" to username, "exact" to "true").isNotEmpty()

  /** The usernames of the accounts that are linked to [fhirUser], such as `Patient/abc`. */
  suspend fun loginsFor(fhirUser: String): List<String> =
    users("q" to "fhirUser:$fhirUser").map { it.getValue("username").jsonPrimitive.content }

  /** Creates [user]; a 409 means the username was taken meanwhile. */
  suspend fun create(user: JsonObject) {
    val url = "$baseUrl/admin/realms/$realm/users"
    val response = send(HttpMethod.Post, url, body = user.toString())
    if (response.status == HttpStatusCode.Conflict) {
      throw CliktError(
        "Keycloak already has a user named ${user.getValue("username").jsonPrimitive.content}"
      )
    }
    if (!response.status.isSuccess()) refused(HttpMethod.Post, url, response.status)
  }

  private suspend fun users(vararg params: Pair<String, String>): List<JsonObject> {
    val url = "$baseUrl/admin/realms/$realm/users"
    val response = send(HttpMethod.Get, url, params = params.toMap())
    if (!response.status.isSuccess()) refused(HttpMethod.Get, url, response.status)
    return Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
  }

  private suspend fun send(
    method: HttpMethod,
    url: String,
    body: String? = null,
    params: Map<String, String> = emptyMap(),
  ) =
    reaching(baseUrl) {
      val bearer = token ?: authenticate().also { token = it }
      client.request(url) {
        this.method = method
        bearerAuth(bearer)
        params.forEach { (name, value) -> parameter(name, value) }
        body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
      }
    }

  private suspend fun authenticate(): String {
    val url = "$baseUrl/realms/$realm/protocol/openid-connect/token"
    val response =
      client.submitForm(
        url,
        parameters {
          append("grant_type", "client_credentials")
          append("client_id", clientId)
          append("client_secret", clientSecret)
        },
      )
    if (!response.status.isSuccess()) refused(HttpMethod.Post, url, response.status)
    return Json.parseToJsonElement(response.bodyAsText())
      .jsonObject
      .getValue("access_token")
      .jsonPrimitive
      .content
  }
}
