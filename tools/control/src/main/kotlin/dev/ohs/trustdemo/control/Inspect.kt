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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val pretty = Json { prettyPrint = true }

/** The step named by [target], as shown by its code or its name; null when none matches. */
fun stepNamed(target: String): Step? =
  Step.entries.firstOrNull { it.code.equals(target, true) || it.name.equals(target, true) }

/**
 * What [step] (default: the current step, else the last) wrote: each expectation met or missing
 * with the records found, or with [asJson] those records in full.
 */
fun inspect(state: StoryState, step: Step? = null, asJson: Boolean = false): String {
  val shown = step ?: StepEngine.currentStep(state) ?: Step.entries.last()
  val steps = expectations.getValue(shown)
  if (asJson) {
    val records =
      steps.associate { expectation ->
        expectation.label to
          JsonArray(
            state.recordsFor(expectation).map {
              pretty.encodeToJsonElement(Resource.serializer(), it)
            }
          )
      }
    return pretty.encodeToString(JsonObject.serializer(), JsonObject(records))
  }
  return buildString {
    appendLine("${shown.code} · ${shown.title} · ${state.metCount(shown)} of ${steps.size} written")
    steps.forEach { expectation ->
      val records = state.recordsFor(expectation)
      val named = records.joinToString(", ") { it.ref() }
      if (state.isMet(expectation)) appendLine("  met      ${expectation.label}: $named")
      else {
        val why = expectation.nearMiss(records, state.dates)
        val found = if (records.isEmpty()) "none found" else "found $named"
        appendLine(
          "  missing  ${expectation.label}: ${listOfNotNull(found, why).joinToString("; ")}"
        )
      }
    }
  }
}
