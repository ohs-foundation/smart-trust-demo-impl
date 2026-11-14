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
package dev.ohs.trustdemo.onesa.feature.questionnaire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class PrefillTest {
  private val gender = "http://hl7.org/fhir/administrative-gender"

  private fun option(code: String) = buildJsonObject {
    putJsonObject("valueCoding") {
      put("system", gender)
      put("code", code)
      put("display", code.replaceFirstChar { it.uppercase() })
    }
  }

  private val male = buildJsonObject {
    putJsonObject("valueCoding") {
      put("system", gender)
      put("code", "male")
    }
  }

  private fun form(vararg options: JsonObject) = buildJsonObject {
    putJsonArray("item") {
      addJsonObject {
        put("linkId", "sex")
        put("type", "choice")
        if (options.isNotEmpty()) putJsonArray("answerOption") { options.forEach { add(it) } }
      }
    }
  }

  private fun JsonObject.sex() = this["item"]!!.jsonArray.single().jsonObject

  private fun JsonObject.selected() =
    this["answerOption"]!!.jsonArray.map {
      it.jsonObject["initialSelected"]?.jsonPrimitive?.booleanOrNull == true
    }

  @Test
  fun aChoiceWithOptionsSelectsTheMatchingOptionInsteadOfAnInitial() {
    val (prefilled, updated) = form(option("male"), option("female")).withInitialAnswer("sex", male)

    assertTrue(updated)
    assertNull(prefilled.sex()["initial"])
    assertEquals(listOf(true, false), prefilled.sex().selected())
  }

  @Test
  fun anItemWithoutOptionsGetsAnInitial() {
    val (prefilled, _) = form().withInitialAnswer("sex", male)

    assertEquals(male, prefilled.sex()["initial"]!!.jsonArray.single())
  }
}
