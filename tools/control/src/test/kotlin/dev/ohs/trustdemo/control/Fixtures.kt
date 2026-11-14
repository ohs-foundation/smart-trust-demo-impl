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
import dev.ohs.fhir.model.r4.ConceptMap
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val ig: List<Resource> = IgResources.all()

private val json = Json

fun Bundle.entryResources(): List<Resource> = entry.mapNotNull { it.resource }

inline fun <reified T : Resource> Bundle.resource(id: String): T =
  entryResources().filterIsInstance<T>().single { it.id == id }

fun Bundle.has(reference: String): Boolean =
  entryResources().any { "${it.resourceType()}/${it.id}" == reference }

fun Group.memberRefs(): Set<String> = member.mapNotNull { it.entity.reference?.value }.toSet()

fun ConceptMap.targetFor(code: String) =
  group.flatMap { it.element }.singleOrNull { it.code?.value == code }

fun Bundle.allReferences(): Set<String> =
  entryResources()
    .flatMap { json.encodeToJsonElement(Resource.serializer(), it).references() }
    .toSet()

fun JsonElement.references(): List<String> =
  when (this) {
    is JsonObject ->
      entries.flatMap { (key, value) ->
        if (key == "reference" && value is JsonPrimitive) listOfNotNull(value.contentOrNull)
        else value.references()
      }
    is JsonArray -> flatMap { it.references() }
    else -> emptyList()
  }

fun Patient.managingOrganizationRef(): String? = managingOrganization?.reference?.value

fun fixtureJson(path: String): JsonObject =
  Json.parseToJsonElement(
      checkNotNull(StoryState::class.java.getResource("/$path")) { "no fixture $path" }.readText()
    )
    .jsonObject

fun fixtureState(path: String): StoryState {
  val fixture = fixtureJson(path)
  val today = LocalDate.parse(fixture.getValue("today").jsonPrimitive.content)
  val found =
    fixture
      .getValue("fragments")
      .jsonArray
      .map { fixtureJson("steps/${it.jsonPrimitive.content}.json") }
      .fold(emptyMap<String, List<Resource>>()) { all, fragment ->
        all +
          fragment.mapValues { (_, records) ->
            records.jsonArray.map { Json.decodeFromJsonElement(Resource.serializer(), it) }
          }
      }
  val resetDay = fixture["resetDay"]?.jsonPrimitive?.content?.let(LocalDate::parse) ?: today
  return StoryState(today, resetDay, found)
}
