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

import dev.ohs.fhir.model.r4.Resource
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A just-enough Medplum: project-scoped storage with search, update, expunge, token and invite.
 * Like the real server, search never returns `meta.project`, and an update keeps the record's
 * project.
 */
class FakeMedplum {
  val stored =
    baseDefinitions
      .associateBy {
        "StructureDefinition/core-${it.getValue("url").jsonPrimitive.content.substringAfterLast('/')}"
      }
      .toMutableMap()
  var invites = 0

  private val engine = MockEngine { request ->
    val url = request.url
    val path = url.encodedPath
    val body = (request.body as? TextContent)?.text.orEmpty()
    val parts = path.removePrefix("/fhir/R4/").split('/')
    val projectId = path.removePrefix("/admin/projects/").substringBefore('/')
    when {
      path == "/oauth2/token" -> respond("""{"access_token":"fake"}""", headers = json)
      path.endsWith("/invite") -> {
        invites++
        val invite = Json.parseToJsonElement(body).jsonObject
        val membership = buildJsonObject {
          put("resourceType", "ProjectMembership")
          put("project", buildJsonObject { put("reference", "Project/$projectId") })
          put("profile", invite["membership"]!!.jsonObject["profile"]!!)
        }
        respond(write("ProjectMembership", UUID.randomUUID().toString(), membership.toString()))
      }
      request.method == HttpMethod.Post && path == "/fhir/R4" -> {
        Json.parseToJsonElement(body).jsonObject.getValue("entry").jsonArray.forEach {
          val resource = it.jsonObject.getValue("resource").jsonObject
          write(
            resource.getValue("resourceType").jsonPrimitive.content,
            resource.getValue("id").jsonPrimitive.content,
            resource.toString(),
          )
        }
        respond("""{"resourceType":"Bundle","type":"transaction-response"}""", headers = json)
      }
      request.method == HttpMethod.Get -> respond(search(parts[0], url.parameters.entries()))
      request.method == HttpMethod.Post && parts.last() == "\$expunge" -> {
        stored.remove("${parts[0]}/${parts[1]}")
        respond("{}", headers = json)
      }
      request.method == HttpMethod.Post ->
        respond(write(parts[0], UUID.randomUUID().toString(), body))
      request.method == HttpMethod.Put -> respond(write(parts[0], parts[1], body))
      else -> error("unexpected ${request.method} ${request.url}")
    }
  }

  private val lenient = Json { ignoreUnknownKeys = true }

  val admin = MedplumAdmin("http://medplum.test", "client", "secret", engine)

  inline fun <reified T : Resource> byIgId(igId: String): T = decoded(igId) as T

  fun decoded(igId: String): Resource =
    lenient.decodeFromJsonElement(Resource.serializer(), stored.values.single { it.igId() == igId })

  private fun write(type: String, id: String, body: String): String {
    val incoming = Json.parseToJsonElement(body).jsonObject
    val project = stored["$type/$id"]?.project()
    val meta = incoming["meta"]?.jsonObject.orEmpty()
    val kept =
      if (project == null || meta.containsKey("project")) incoming
      else
        JsonObject(incoming + ("meta" to JsonObject(meta + ("project" to JsonPrimitive(project)))))
    val resource = JsonObject(kept + ("id" to JsonPrimitive(id)))
    stored["$type/$id"] = resource
    return resource.toString()
  }

  private fun search(type: String, params: Set<Map.Entry<String, List<String>>>): String {
    val filters = params.filterNot { it.key.startsWith("_count") }
    val matches =
      stored
        .filterKeys { it.startsWith("$type/") }
        .values
        .filter { resource ->
          filters.all { (name, values) -> resource.matches(name, values.single()) }
        }
    return buildJsonObject {
        put("resourceType", "Bundle")
        put(
          "entry",
          JsonArray(matches.map { buildJsonObject { put("resource", it.withoutProject()) } }),
        )
      }
      .toString()
  }

  private fun JsonObject.matches(name: String, value: String): Boolean =
    when (name) {
      "_id" -> this["id"]?.jsonPrimitive?.contentOrNull in value.split(',')
      "_project" -> project() == value
      "identifier" -> identifiers().any { it == value || it.substringAfter('|') == value }
      else -> {
        val field = this[name]
        field?.let { (it as? JsonObject)?.get("reference") ?: it }?.jsonPrimitive?.contentOrNull ==
          value
      }
    }

  private fun JsonObject.project() =
    this["meta"]?.jsonObject?.get("project")?.jsonPrimitive?.contentOrNull

  private fun JsonObject.withoutProject(): JsonObject {
    val meta = this["meta"]?.jsonObject ?: return this
    return JsonObject(this + ("meta" to JsonObject(meta - "project")))
  }

  private companion object {
    val json = headersOf(HttpHeaders.ContentType, "application/fhir+json")
  }
}

fun JsonObject.identifiers(): List<String> =
  this["identifier"]?.jsonArray.orEmpty().map {
    val identifier = it.jsonObject
    "${identifier["system"]?.jsonPrimitive?.contentOrNull}|${identifier["value"]?.jsonPrimitive?.contentOrNull}"
  }

fun JsonObject.hasIdentifier(value: String) = identifiers().any { it.substringAfter('|') == value }

fun JsonObject.igId(): String? =
  identifiers().firstOrNull { it.startsWith("$IG_ID_SYSTEM|") }?.substringAfter('|')
