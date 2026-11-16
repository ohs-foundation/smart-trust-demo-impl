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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private typealias Element = MutableMap<String, JsonElement>

/**
 * Gives an IG profile the snapshot Medplum validates against; the IG ships only the differential.
 * [definition] returns a StructureDefinition with its snapshot, by canonical url. Covers what the
 * IG's profiles use: constraints on an element, slices, and elements of a datatype.
 */
class ProfileSnapshots(private val definition: suspend (url: String) -> JsonObject) {
  suspend fun withSnapshot(profile: JsonObject): JsonObject {
    val elements = snapshotOf(profile.text("baseDefinition")).toMutableList()
    profile.getValue("differential").jsonObject.getValue("element").jsonArray.forEach {
      apply(elements, it.jsonObject)
    }
    return JsonObject(profile + ("snapshot" to JsonObject(mapOf("element" to elements.toArray()))))
  }

  private suspend fun apply(elements: MutableList<Element>, constraint: JsonObject) {
    val id = constraint.text("id")
    val target =
      elements.find { it.text("id") == id }
        ?: if (":" in id.substringAfterLast('.') && id.endsWith(constraint.text("sliceName")))
          slice(elements, id)
        else {
          expand(elements, id.substringBeforeLast('.'))
          checkNotNull(elements.find { it.text("id") == id }) { "$id is not in the base" }
        }
    constraint
      .filterKeys { it != "id" && it != "path" }
      .forEach { (key, value) -> target[key] = value }
  }

  private fun slice(elements: MutableList<Element>, id: String): Element {
    val parentId = id.substringBeforeLast(':')
    val parent = elements.single { it.text("id") == parentId }
    val copy = (parent - "slicing").toMutableMap()
    copy["id"] = JsonPrimitive(id)
    copy["sliceName"] = JsonPrimitive(id.substringAfterLast(':'))
    val last =
      elements.indexOfLast {
        val other = it.text("id")
        other.startsWith("$parentId.") || other.startsWith("$parentId:")
      }
    elements.add(maxOf(last, elements.indexOf(parent)) + 1, copy)
    return copy
  }

  private suspend fun expand(elements: MutableList<Element>, parentId: String) {
    val parent = elements.single { it.text("id") == parentId }
    if (elements.any { it.text("id").startsWith("$parentId.") }) return
    val type = parent["type"]?.jsonArray?.firstOrNull()?.jsonObject?.text("code") ?: return
    val fields = snapshotOf("$CORE/$type")
    val root = fields.first().text("id")
    val children =
      fields.drop(1).map { field ->
        field.toMutableMap().also {
          it["id"] = JsonPrimitive(parentId + field.text("id").removePrefix(root))
          it["path"] = JsonPrimitive(parent.text("path") + field.text("path").removePrefix(root))
        }
      }
    elements.addAll(elements.indexOf(parent) + 1, children)
  }

  private suspend fun snapshotOf(url: String): List<Element> =
    definition(url).getValue("snapshot").jsonObject.getValue("element").jsonArray.map {
      it.jsonObject.toMutableMap()
    }

  private fun List<Element>.toArray() = JsonArray(map { JsonObject(it) })

  private fun Map<String, JsonElement>.text(name: String) = getValue(name).jsonPrimitive.content

  private companion object {
    const val CORE = "http://hl7.org/fhir/StructureDefinition"
  }
}
