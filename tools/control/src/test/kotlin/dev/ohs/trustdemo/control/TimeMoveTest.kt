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
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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

private val now = Clock.fixed(Instant.parse("2026-11-13T02:59:00Z"), ZoneOffset.ofHours(7))

private suspend fun world(state: StoryState, extra: List<JsonObject> = emptyList()): World {
  val hapi = FakeStoryHapi(records.records("hapi") + extra)
  val medplum = FakeMedplum().also { it.seed(records.records("medplum")) }
  return World(hapi, medplum, TimeMove({ state }, hapi.server, medplum.admin, now))
}

private fun Immunization.occurrenceDate(): String = fields().text("occurrenceDateTime")!!

class TimeMoveTest {
  @Test
  fun moveIsRefusedBeforeJ3IsComplete() = runTest {
    assertFailsWith<MoveRefused> {
      world(fixtureState("steps/mid-J3B.json")).move.plan(withYearDoses = true)
    }
    assertFailsWith<MoveRefused> {
      world(fixtureState("steps/after-move.json")).move.plan(withYearDoses = false)
    }
    val partial = buildJsonObject {
      put("resourceType", "Patient")
      put("id", "Partial")
      put("birthDate", "2026-10")
    }
    assertFailsWith<MoveRefused> {
      world(fixtureState("steps/before-YEAR.json"), listOf(partial)).move.plan(false)
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
    assertTrue(
      plan.changes.any { it.path == "item[0].item[0].answer[0].item[0].answer[0].valueDate" }
    )
    move.apply(plan)
    assertEquals("2025-11-16", hapi.stored.getValue("Patient/Somsak").text("birthDate"))
    assertEquals("2025-11-16", medplum.stored.getValue("Patient/TaifafekiSomsak").text("birthDate"))
    assertEquals(
      "2026-11-14T07:00:00Z",
      medplum.stored.getValue("DocumentReference/Card").text("date"),
    )
    val half = world(fixtureState("steps/half-moved.json")).move.plan(withYearDoses = false)
    assertEquals(setOf(Server.HAPI), half.changes.map { it.server }.toSet())
  }

  @Test
  fun yearDosesLandOnTheIgDatesUnderTheNewAnchor() = runTest {
    val (hapi, _, move) = world(fixtureState("steps/before-YEAR-day-before.json"))
    val plan = move.plan(withYearDoses = true)
    val doses = plan.yearDoses
    val before = hapi.stored.size
    move.apply(plan)
    assertEquals(before + 4, hapi.stored.size)
    assertEquals(
      listOf("2025-12-28", "2025-12-28", "2025-12-28", "2026-08-15"),
      doses.map { it.occurrenceDate() },
    )
  }

  @Test
  fun expireConsentsEndsActiveConsentsOneMinuteBeforeNow() = runTest {
    val (hapi, _, move) = world(fixtureState("steps/before-J41.json"))
    val before = hapi.stored.toMap()
    move.expireConsents()
    val end = "2026-11-13T09:58:00+07:00"
    listOf("ActiveConsent", "TodayConsent").forEach {
      val consent = hapi.stored.getValue("Consent/$it")
      assertEquals(end, consent.provisionPeriod().text("end"))
      assertTrue(
        OffsetDateTime.parse(consent.provisionPeriod().text("start")!!) <= OffsetDateTime.parse(end)
      )
      assertEquals(before.getValue("Consent/$it").withoutEnd(), consent.withoutEnd())
    }
    assertEquals(before.getValue("Consent/RejectedConsent"), hapi.stored["Consent/RejectedConsent"])
  }
}

private fun JsonObject.provisionPeriod() =
  getValue("provision").jsonObject.getValue("period").jsonObject

private fun JsonObject.withoutEnd() =
  JsonObject(
    this +
      ("provision" to
        JsonObject(
          getValue("provision").jsonObject + ("period" to JsonObject(provisionPeriod() - "end"))
        ))
  )
