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
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun fixtureJson(path: String): JsonObject =
  Json.parseToJsonElement(
      checkNotNull(StepsTest::class.java.getResource("/$path")) { "no fixture $path" }.readText()
    )
    .jsonObject

private fun fixtureState(path: String): StoryState {
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
  return StoryState(today, today, found)
}

class StepsTest {
  @Test
  fun statusNamesTheStepFromItsFixture() =
    Step.entries.forEach { step ->
      assertEquals(
        step,
        StepEngine.currentStep(fixtureState("steps/before-${step.name}.json")),
        "$step",
      )
    }

  @Test
  fun allStepsDoneGivesNoCurrentStep() =
    assertNull(StepEngine.currentStep(fixtureState("steps/after-J41.json")))

  @Test
  fun j2ShowsAsNotYetSyncedWhenOnlyJ1IsOnTheServers() =
    assertEquals(
      "in progress or not yet synced",
      statusLine(fixtureState("steps/before-J2.json"), Step.J2),
    )

  @Test
  fun inspectListsMetAndMissingRecordsForTheCurrentStep() {
    val out = inspect(fixtureState("steps/mid-J3B.json"))
    assertContains(out, "J3b · Map and schedule · 2 of 5 written")
    assertContains(out, "status active, expected completed")
  }
}
