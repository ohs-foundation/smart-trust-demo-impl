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

import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val DATE_PREFIX = Regex("""^\d{4}-\d{2}-\d{2}""")
private val STEP = Regex("""(\w+)(?:\[(\d+)])?""")
private const val IG_ID_PREFIX = "TrustDemo"
private const val DATE_LENGTH = 10

/** Where the story's dates sit in each resource type; the time move shifts exactly these. */
object StoryDates {
  val paths: Map<String, List<String>> =
    mapOf(
      "Patient" to listOf("birthDate"),
      "Immunization" to listOf("occurrenceDateTime", "recorded"),
      "QuestionnaireResponse" to listOf("authored", "item.answer.valueDate"),
      "ServiceRequest" to listOf("authoredOn"),
      "Provenance" to listOf("recorded"),
      "ImmunizationRecommendation" to listOf("date", "recommendation.dateCriterion.value"),
      "Task" to listOf("authoredOn", "restriction.period.start", "restriction.period.end"),
      "Group" to listOf("member.period.start", "member.period.end"),
      "Consent" to listOf("dateTime", "provision.period.start", "provision.period.end"),
      "Composition" to listOf("date"),
      "Bundle" to listOf("timestamp"),
      "AuditEvent" to listOf("recorded"),
      "List" to listOf("date"),
      "DocumentReference" to listOf("date"),
    )

  /**
   * Seeded base records keep the IG's ids and their dates; the household group is the exception.
   */
  fun isBase(resource: JsonObject): Boolean =
    resource.text("resourceType") != "Group" &&
      resource.text("id")?.startsWith(IG_ID_PREFIX) == true

  /** Each date in [resource] along [paths], as its indexed path and its stored value. */
  fun datesIn(resource: JsonObject): List<Pair<String, String>> {
    val type = resource.text("resourceType")!!
    return paths.getValue(type).flatMap { path ->
      val segments = path.split('.')
      buildList { collect(resource, segments, segments, "", this) }
        .onEach { (at, value) ->
          if (!DATE_PREFIX.containsMatchIn(value)) {
            throw MoveRefused(
              "$type/${resource.text("id")} $at holds the partial date $value; " +
                "the move shifts whole days only"
            )
          }
        }
    }
  }

  /** [resource] with the date at [path] moved by [days], which must still hold [from]. */
  fun shifted(resource: JsonObject, path: String, from: LocalDate, days: Long): JsonObject =
    replace(resource, STEP.findAll(path).toList(), from, days) as JsonObject

  fun dateOf(value: String): LocalDate = LocalDate.parse(value.take(DATE_LENGTH))

  fun shift(value: String, days: Long): String =
    dateOf(value).plusDays(days).toString() + value.drop(DATE_LENGTH)

  /** Items nest, also under answers, so a path through `item` is tried again under each one. */
  private fun collect(
    node: JsonElement,
    segments: List<String>,
    full: List<String>,
    at: String,
    out: MutableList<Pair<String, String>>,
  ) {
    if (segments.isEmpty()) {
      val value = (node as? JsonPrimitive)?.takeIf { it.isString }?.content
      if (value != null) out += at to value
      return
    }
    val name = segments.first()
    val child = (node as? JsonObject)?.get(name) ?: return
    val elements =
      if (child is JsonArray) child.mapIndexed { index, element -> "$name[$index]" to element }
      else listOf(name to child)
    elements.forEach { (step, element) ->
      val here = if (at.isEmpty()) step else "$at.$step"
      collect(element, segments.drop(1), full, here, out)
      if (full.first() == "item" && name in listOf("item", "answer")) {
        collect(element, full, full, here, out)
      }
    }
  }

  private fun replace(
    node: JsonElement,
    steps: List<MatchResult>,
    from: LocalDate,
    days: Long,
  ): JsonElement {
    if (steps.isEmpty()) {
      val value = (node as JsonPrimitive).content
      check(dateOf(value) == from) { "$value is not $from" }
      return JsonPrimitive(shift(value, days))
    }
    val (name, index) = steps.first().destructured
    val obj = node as JsonObject
    val child = obj.getValue(name)
    val rest = steps.drop(1)
    val replaced =
      if (index.isEmpty()) replace(child, rest, from, days)
      else
        JsonArray(
          (child as JsonArray).mapIndexed { i, e ->
            if (i == index.toInt()) replace(e, rest, from, days) else e
          }
        )
    return JsonObject(obj + (name to replaced))
  }
}
