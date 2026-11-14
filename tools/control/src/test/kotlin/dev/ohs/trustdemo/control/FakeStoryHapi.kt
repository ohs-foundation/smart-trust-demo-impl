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

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A just-enough HAPI: records by type and id, with search, update and transaction create. */
class FakeStoryHapi(records: List<JsonObject> = emptyList()) {
  val stored = records.associateBy { "${it.text("resourceType")}/${it.text("id")}" }.toMutableMap()

  private val engine = MockEngine { request ->
    val parts = request.url.encodedPath.removePrefix("/fhir").trim('/').split('/')
    val body = (request.body as? TextContent)?.text.orEmpty()
    when {
      request.method == HttpMethod.Get ->
        respond(search(parts[0], request.url.parameters.entries()), headers = fhirJson)
      request.method == HttpMethod.Put -> {
        stored["${parts[0]}/${parts[1]}"] = Json.parseToJsonElement(body).jsonObject
        respond(body, headers = fhirJson)
      }
      request.method == HttpMethod.Post -> {
        Json.parseToJsonElement(body).jsonObject.getValue("entry").jsonArray.forEach {
          val entry = it.jsonObject
          val resource = entry.getValue("resource").jsonObject
          val put = entry.getValue("request").jsonObject.text("method") == "PUT"
          val key =
            if (put) entry.getValue("request").jsonObject.text("url")!!
            else "${resource.text("resourceType")}/${UUID.randomUUID()}"
          stored[key] = JsonObject(resource + ("id" to JsonPrimitive(key.substringAfter('/'))))
        }
        respond("""{"resourceType":"Bundle","type":"transaction-response"}""", headers = fhirJson)
      }
      else -> error("unexpected ${request.method} ${request.url}")
    }
  }

  val server = FhirServer("http://hapi.test/fhir", { null }, engine)

  private fun search(type: String, params: Set<Map.Entry<String, List<String>>>): String {
    val filters = params.filterNot { it.key == "_count" }
    val matches =
      stored
        .filterKeys { it.startsWith("$type/") }
        .values
        .filter { record ->
          filters.all { (name, values) -> record.matches(name, values.single()) }
        }
    return buildJsonObject {
        put("resourceType", "Bundle")
        put("type", "searchset")
        put("entry", JsonArray(matches.map { buildJsonObject { put("resource", it) } }))
      }
      .toString()
  }

  private fun JsonObject.matches(name: String, value: String): Boolean =
    when (name) {
      "_id" -> text("id") == value
      "_profile" ->
        this["meta"]?.jsonObject?.get("profile")?.jsonArray.orEmpty().any {
          it is JsonPrimitive && it.content == value
        }
      "family" -> names().any { it.text("family") == value }
      "given" ->
        names().any { name ->
          name["given"]?.jsonArray.orEmpty().any { it is JsonPrimitive && it.content == value }
        }
      else -> text(name) == value
    }

  private fun JsonObject.names() = this["name"]?.jsonArray.orEmpty().map { it.jsonObject }

  private companion object {
    val fhirJson = headersOf(HttpHeaders.ContentType, "application/fhir+json")
  }
}
