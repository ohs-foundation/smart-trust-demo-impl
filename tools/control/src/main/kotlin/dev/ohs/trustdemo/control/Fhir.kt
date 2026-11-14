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

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Resource
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.net.URI
import kotlinx.serialization.json.Json

private val fhirJson = Json { ignoreUnknownKeys = true }
private val fhirContentType = ContentType.parse("application/fhir+json")
private const val EXPUNGE_EVERYTHING =
  """{"resourceType":"Parameters","parameter":[{"name":"expungeEverything","valueBoolean":true}]}"""

/** The FHIR type name of this resource, as used in `Type/id` references. */
fun Resource.resourceType(): String = this::class.simpleName!!

/** A FHIR REST server; [token] supplies a bearer token, or null when the server has no auth. */
class FhirServer(
  private val baseUrl: String,
  private val token: suspend () -> String?,
  engine: HttpClientEngine,
) {
  private val client = HttpClient(engine)

  suspend fun search(type: String, params: Map<String, String>): List<Resource> {
    val response = call(HttpMethod.Get, "$baseUrl/$type", params = params)
    return decode<Bundle>(response).entry.mapNotNull { it.resource }
  }

  /** Like [search], but follows the bundle's next links until the results run out. */
  suspend fun searchAll(type: String, params: Map<String, String>): List<Resource> {
    var page = decode<Bundle>(call(HttpMethod.Get, "$baseUrl/$type", params = params))
    val found = page.entry.mapNotNull { it.resource }.toMutableList()
    while (true) {
      val next = page.link.firstOrNull { it.relation.value == "next" }?.url?.value ?: break
      page = decode(call(HttpMethod.Get, "$baseUrl?${URI(next).rawQuery}"))
      found += page.entry.mapNotNull { it.resource }
    }
    return found
  }

  suspend fun transaction(bundle: Bundle): Bundle =
    decode(call(HttpMethod.Post, baseUrl, body = encode(bundle)))

  suspend fun update(resource: Resource): Resource =
    decode(
      call(
        HttpMethod.Put,
        "$baseUrl/${resource.resourceType()}/${resource.id}",
        body = encode(resource),
      )
    )

  suspend fun expungeEverything() {
    val response =
      call(
        HttpMethod.Post,
        "$baseUrl/\$expunge",
        body = TextContent(EXPUNGE_EVERYTHING, fhirContentType),
      )
    if (!response.status.isSuccess()) {
      refused(HttpMethod.Post, "$baseUrl/\$expunge", response.status)
    }
  }

  suspend fun ping() {
    val response = call(HttpMethod.Get, "$baseUrl/metadata")
    if (!response.status.isSuccess()) {
      refused(HttpMethod.Get, "$baseUrl/metadata", response.status)
    }
  }

  private suspend fun call(
    method: HttpMethod,
    url: String,
    params: Map<String, String> = emptyMap(),
    body: TextContent? = null,
  ): HttpResponse {
    val bearer = token()
    return reaching(baseUrl) {
      client.request(url) {
        this.method = method
        header(HttpHeaders.Accept, fhirContentType.toString())
        bearer?.let { bearerAuth(it) }
        params.forEach { (name, value) -> parameter(name, value) }
        body?.let { setBody(it) }
      }
    }
  }

  private fun encode(resource: Resource) =
    TextContent(fhirJson.encodeToString(Resource.serializer(), resource), fhirContentType)

  private suspend inline fun <reified T : Resource> decode(response: HttpResponse): T {
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) {
      refused(response.request.method, response.request.url.toString(), response.status)
    }
    return fhirJson.decodeFromString(Resource.serializer(), text) as T
  }
}
