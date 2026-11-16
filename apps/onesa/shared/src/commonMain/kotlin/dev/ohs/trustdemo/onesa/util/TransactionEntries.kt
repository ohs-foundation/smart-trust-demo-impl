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
package dev.ohs.trustdemo.onesa.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val DESIGN = "http://smart.who.int/trust-demo/StructureDefinition"

/** A new record under a client-assigned id: the gateway's audit cannot record `urn:uuid`. */
fun createEntry(type: String, id: String, resource: JsonObject): JsonObject =
  putEntry("$type/$id", JsonObject(resource + ("id" to JsonPrimitive(id))))

/** A PUT; with [ifMatch] the server refuses it when the record is no longer at that version. */
fun putEntry(url: String, resource: JsonObject, ifMatch: String? = null): JsonObject =
  buildJsonObject {
    put("resource", resource)
    putJsonObject("request") {
      put("method", "PUT")
      put("url", url)
      ifMatch?.let { put("ifMatch", it) }
    }
  }

/** [this] without the server's `versionId` and `lastUpdated`, to be written back. */
fun JsonObject.unversioned(): JsonObject {
  val kept = this["meta"]?.jsonObject?.filterKeys { it != "versionId" && it != "lastUpdated" }
  return if (kept.isNullOrEmpty()) JsonObject(this - "meta")
  else JsonObject(this + ("meta" to JsonObject(kept)))
}

fun deleteEntry(url: String): JsonObject = buildJsonObject {
  putJsonObject("request") {
    put("method", "DELETE")
    put("url", url)
  }
}

/** `meta` naming one of the IG's profiles by its short name. */
fun profile(name: String): JsonObject = buildJsonObject {
  putJsonArray("profile") { add(JsonPrimitive("$DESIGN/$name")) }
}
