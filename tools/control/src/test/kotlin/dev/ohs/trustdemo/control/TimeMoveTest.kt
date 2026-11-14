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

import dev.ohs.fhir.model.r4.Immunization
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val records: JsonObject =
  Json.parseToJsonElement(
      checkNotNull(StoryState::class.java.getResource("/move/story-records.json")).readText()
    )
    .jsonObject

private fun JsonObject.records(name: String) = getValue(name).jsonArray.map { it.jsonObject }

private suspend fun FakeMedplum.seed(records: List<JsonObject>) {
  admin.searchJson("Patient", emptyMap())
  val project = stored.keys.single { it.startsWith("Project/") }.substringAfter('/')
  records.forEach {
    stored["${it.text("resourceType")}/${it.text("id")}"] =
      JsonObject(it + ("meta" to JsonObject(mapOf("project" to JsonPrimitive(project)))))
  }
}

private data class World(val hapi: FakeStoryHapi, val medplum: FakeMedplum, val move: TimeMove)

private suspend fun world(state: StoryState): World {
  val hapi = FakeStoryHapi(records.records("hapi"))
  val medplum = FakeMedplum().also { it.seed(records.records("medplum")) }
  return World(hapi, medplum, TimeMove({ state }, hapi.server, medplum.admin))
}

private fun Immunization.occurrenceDate(): String = fields().text("occurrenceDateTime")!!

class TimeMoveTest {
  @Test
  fun moveIsRefusedBeforeJ3IsComplete() = runTest {
    assertFailsWith<MoveRefused> {
      world(fixtureState("steps/mid-J3B.json")).move.plan(withYearDoses = true)
    }
  }

  @Test
  fun moveShiftsEveryStoryDateByMinus335AndKeepsEveryGap() = runTest {
    val (hapi, medplum, move) = world(fixtureState("steps/before-YEAR.json"))
    val plan = move.plan(withYearDoses = false)
    assertEquals(-335, plan.shiftDays)
    assertTrue(plan.changes.all { ChronoUnit.DAYS.between(it.from, it.to) == -335L })
    assertTrue(plan.changes.none { it.path.startsWith("meta") })
    assertEquals(
      StoryDates.paths.keys,
      plan.changes.map { it.reference.substringBefore('/') }.toSet(),
    )
    assertEquals(Server.entries.toSet(), plan.changes.map { it.server }.toSet())
    assertTrue(
      plan.changes.none { it.reference.contains("TrustDemo") && !it.reference.startsWith("Group") }
    )
    assertTrue(plan.changes.any { it.path == "item[0].item[0].answer[0].valueDate" })
    move.apply(plan)
    assertEquals("2025-11-16", hapi.stored.getValue("Patient/Somsak").text("birthDate"))
    assertEquals("2025-11-16", medplum.stored.getValue("Patient/TaifafekiSomsak").text("birthDate"))
    assertEquals(
      "2026-11-14T07:00:00Z",
      medplum.stored.getValue("DocumentReference/Card").text("date"),
    )
  }

  @Test
  fun yearDosesLandOnTheIgDatesUnderTheNewAnchor() = runTest {
    val doses =
      world(fixtureState("steps/before-YEAR-day-before.json"))
        .move
        .plan(withYearDoses = true)
        .yearDoses
    assertEquals(
      listOf("2025-12-28", "2025-12-28", "2025-12-28", "2026-08-15"),
      doses.map { it.occurrenceDate() },
    )
  }

  @Test
  fun expireConsentsEndsActiveConsentsYesterday() = runTest {
    val (hapi, _, move) = world(fixtureState("steps/before-J41.json"))
    move.expireConsents(LocalDate.parse("2026-11-13"))
    assertEquals("2026-11-12", hapi.stored.getValue("Consent/ActiveConsent").provisionEnd())
    assertEquals("2026-11-09", hapi.stored.getValue("Consent/RejectedConsent").provisionEnd())
  }
}

private fun JsonObject.provisionEnd() =
  getValue("provision").jsonObject.getValue("period").jsonObject.text("end")
