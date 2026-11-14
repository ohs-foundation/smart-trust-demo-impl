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
package dev.ohs.trustdemo.vhl

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val fhirContentType = ContentType.parse("application/fhir+json")

/** A FHIR server the service calls; a caller's bearer token is passed through when given. */
class FhirBackend(
  private val baseUrl: String,
  engine: HttpClientEngine,
  private val relaysCaller: Boolean = false,
) {
  private val client = HttpClient(engine)

  suspend fun read(
    path: String,
    params: Map<String, String> = emptyMap(),
    bearer: String? = null,
  ): JsonObject = get("$baseUrl/$path", params, bearer)

  /** Every resource a search finds, following the bundle's next links to the last page. */
  suspend fun search(
    type: String,
    params: Map<String, String>,
    bearer: String? = null,
  ): List<JsonObject> {
    var page = read(type, params, bearer)
    val found = page.resources().toMutableList()
    while (true) {
      val next =
        page["link"]
          ?.jsonArray
          ?.map { it.jsonObject }
          ?.firstOrNull { it.text("relation") == "next" } ?: break
      page = get("$baseUrl?${URI(next.text("url")!!).rawQuery}", emptyMap(), bearer)
      found += page.resources()
    }
    return found
  }

  suspend fun transaction(bundle: JsonObject) {
    val response =
      client.post(baseUrl) {
        header(HttpHeaders.Accept, fhirContentType.toString())
        setBody(TextContent(bundle.toString(), fhirContentType))
      }
    decode(response.status, response.bodyAsText(), "POST transaction")
  }

  suspend fun update(path: String, resource: JsonObject) {
    val response =
      client.put("$baseUrl/$path") {
        header(HttpHeaders.Accept, fhirContentType.toString())
        setBody(TextContent(resource.toString(), fhirContentType))
      }
    decode(response.status, response.bodyAsText(), "PUT")
  }

  private suspend fun get(url: String, params: Map<String, String>, bearer: String?): JsonObject {
    val response =
      client.get(url) {
        header(HttpHeaders.Accept, fhirContentType.toString())
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        params.forEach { (name, value) -> parameter(name, value) }
      }
    return decode(response.status, response.bodyAsText(), "GET")
  }

  private fun JsonObject.resources() =
    this["entry"]?.jsonArray?.mapNotNull { it.jsonObject["resource"]?.jsonObject }.orEmpty()

  private fun decode(status: HttpStatusCode, text: String, what: String): JsonObject {
    if (!status.isSuccess()) {
      val denied =
        relaysCaller &&
          (status == HttpStatusCode.Forbidden || status == HttpStatusCode.Unauthorized)
      throw BackendRefused(denied, "$what answered ${status.value}", status.value)
    }
    return Json.parseToJsonElement(text).jsonObject
  }
}

/** A backend answered with a failure; [deniedCaller] when the caller's own token was refused. */
class BackendRefused(val deniedCaller: Boolean, message: String, val status: Int = 0) :
  Exception(message)
