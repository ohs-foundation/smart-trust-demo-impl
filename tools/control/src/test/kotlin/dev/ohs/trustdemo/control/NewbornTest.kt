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

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class NewbornTest {
  private val clock = Clock.fixed(Instant.parse("2026-11-20T08:00:00Z"), ZoneOffset.UTC)
  private val card = "HC1:NEWBORN-CARD"

  @Test
  fun aNewbornIsFoundByHospitalNumberAndThroughItsCardsOnOnesa() = runTest {
    val state = engine(NEW_NUMBER, hapiWithBothChildren()).state()

    assertEquals(Newborn(NEW_NUMBER, listOf("NewKid")), state.newborn)
    val j1 = state.expectations.getValue(Step.J1).first()
    assertEquals("The newborn's patient record", j1.label)
    assertTrue(state.isMet(j1))
    assertEquals(listOf("Patient/NewKid"), recordsOf(state, Step.J2, "record on Onesa"))
    assertEquals(listOf("Immunization/NewDose"), recordsOf(state, Step.J2, "Reported doses"))
    assertEquals(Step.J1, StepEngine.currentStep(state))
    assertContains(inspect(state, Step.J2), "J2 · Household registration · 1 of 5 written")
  }

  @Test
  fun aNewbornNotYetOnOnesaMeetsNothingThereWhileSomsakIsRegistered() = runTest {
    val state = engine(NEW_NUMBER, FakeStoryHapi(listOf(somsak))).state()

    assertEquals(Newborn(NEW_NUMBER, emptyList()), state.newborn)
    assertEquals(0, state.metCount(Step.J2))
    assertContains(currentStep(state), "enter the newborn's discharge")
  }

  @Test
  fun theDefaultStillFindsSomsakByName() = runTest {
    val state = engine(null, hapiWithBothChildren()).state()

    assertEquals(Newborn.SOMSAK, state.newborn)
    assertEquals(listOf("Patient/Somsak"), recordsOf(state, Step.J2, "record on Onesa"))
  }

  private fun recordsOf(state: StoryState, step: Step, label: String) =
    state.expectations
      .getValue(step)
      .filter { label in it.label }
      .flatMap { state.recordsFor(it) }
      .map { it.ref() }

  private suspend fun engine(hospitalNumber: String?, hapi: FakeStoryHapi): StepEngine {
    val medplum = FakeMedplum()
    medplum.seedKesaro(NEW_NUMBER, card)
    return if (hospitalNumber == null) StepEngine(hapi.server, medplum.admin, clock)
    else StepEngine(hapi.server, medplum.admin, clock, hospitalNumber)
  }

  private fun hapiWithBothChildren() =
    FakeStoryHapi(
      listOf(
        somsak,
        patient("NewKid", "Mwangi", "Baraka"),
        immunization("NewDose", "NewKid", fingerprint(card)),
        immunization("SomsakDose", "Somsak", "someone-elses"),
      )
    )

  private val somsak = patient("Somsak", "Sengmany", "Somsak")

  private fun patient(id: String, family: String, given: String) = buildJsonObject {
    put("resourceType", "Patient")
    put("id", id)
    put(
      "name",
      buildJsonArray {
        add(
          buildJsonObject {
            put("family", family)
            put("given", buildJsonArray { add(JsonPrimitive(given)) })
          }
        )
      },
    )
    put("birthDate", "2026-11-01")
  }

  private fun immunization(id: String, patient: String, fingerprint: String) = buildJsonObject {
    put("resourceType", "Immunization")
    put("id", id)
    put("status", "completed")
    put("primarySource", false)
    put("occurrenceDateTime", "2026-11-01")
    put(
      "vaccineCode",
      buildJsonObject {
        put(
          "coding",
          buildJsonArray { add(buildJsonObject { put("code", "vp-bcg-freeze-dried") }) },
        )
      },
    )
    put("patient", buildJsonObject { put("reference", "Patient/$patient") })
    put(
      "identifier",
      buildJsonArray {
        add(
          buildJsonObject {
            put("system", FINGERPRINT_SYSTEM)
            put("value", fingerprint)
          }
        )
      },
    )
  }

  private companion object {
    const val NEW_NUMBER = "CRH-HN-70-000001"
  }
}

/** Seeds Medplum with a Patient and an issued card for [hospitalNumber]. */
private suspend fun FakeMedplum.seedKesaro(hospitalNumber: String, hc1: String) {
  admin.seedBase(ig)
  val project =
    stored.values.first { it.igId() == "TrustDemoKesaroHospital" }.getValue("meta").jsonObject
  fun record(type: String, id: String, extra: JsonObject) {
    stored["$type/$id"] =
      JsonObject(
        buildJsonObject {
          put("resourceType", type)
          put("id", id)
          put("meta", JsonObject(project))
          put(
            "identifier",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("system", HOSPITAL_NUMBER_SYSTEM)
                  put("value", hospitalNumber)
                }
              )
            },
          )
        } + extra
      )
  }
  record("Patient", "NewKesaroPatient", JsonObject(emptyMap()))
  record(
    "DocumentReference",
    "NewCard",
    buildJsonObject {
      put("status", "current")
      put(
        "content",
        buildJsonArray {
          add(
            buildJsonObject {
              put(
                "attachment",
                buildJsonObject {
                  put("data", Base64.getEncoder().encodeToString(hc1.toByteArray()))
                },
              )
            }
          )
        },
      )
    },
  )
}
