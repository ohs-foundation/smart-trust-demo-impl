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
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.trustdemo.trustkit.CardSigner
import dev.ohs.trustdemo.trustkit.StoryClock
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class StepsTest {
  @Test
  fun statusNamesTheStepFromItsFixture() {
    Step.entries.forEach { step ->
      assertEquals(
        step,
        StepEngine.currentStep(fixtureState("steps/before-${step.name}.json")),
        "$step",
      )
    }
    val partial = fixtureState("steps/j1-partial.json")
    assertEquals(Step.J1, StepEngine.currentStep(partial))
    assertEquals("in progress", statusLine(partial, Step.J1))
    val skipped = fixtureState("steps/j2-without-J1.json")
    assertEquals(Step.J1, StepEngine.currentStep(skipped))
    assertContains(status(skipped), "! J1 incomplete although J2 records exist")
  }

  @Test
  fun allStepsDoneGivesNoCurrentStep() =
    listOf("after-J41", "after-move").forEach {
      assertNull(StepEngine.currentStep(fixtureState("steps/$it.json")), it)
    }

  @Test
  fun j2ShowsAsNotYetSyncedWhenOnlyJ1IsOnTheServers() =
    assertEquals(
      "in progress or not yet synced",
      statusLine(fixtureState("steps/before-J2.json"), Step.J2),
    )

  @Test
  fun inspectListsMetAndMissingRecordsForTheCurrentStep() {
    val out = inspect(fixtureState("steps/mid-J3B.json"))
    assertContains(out, "J3b · A foreign record used for the next vaccine · 2 of 5 written")
    assertContains(out, "status active, expected completed")
  }

  @Test
  fun theStoryAnchorsAreTheIgsDates() {
    fun dateOf(id: String, vararg path: String): String? {
      val record = ig.single { it.id == id }.fields()
      return path
        .dropLast(1)
        .fold(record) { node, name -> node.getValue(name).jsonObject }
        .text(path.last())
        ?.take(10)
    }
    assertEquals(IG_BIRTH.toString(), dateOf("TrustDemoSomsak", "birthDate"))
    assertEquals(IG_J3.toString(), dateOf("TrustDemoJ3Verification", "recorded"))
    assertEquals(IG_J40.toString(), dateOf("TrustDemoJ4Consent", "provision", "period", "start"))
  }

  @Test
  fun resetDayComesFromTheIssuedCardNotItsUploadTime() = runTest {
    val resetDay = LocalDate.parse("2026-11-14")
    val medplum = FakeMedplum()
    val somsak = ig.filterIsInstance<Patient>().single { it.id == "TrustDemoTaifafekiSomsak" }
    val dose = ig.filterIsInstance<Immunization>().single { it.id == "TrustDemoJ1HepB" }
    val hc1 = CardSigner.sign(dose, somsak, StoryClock(IG_J3, resetDay))
    medplum.admin.seedBase(ig)
    medplum.stored["DocumentReference/card"] = buildJsonObject {
      put("resourceType", "DocumentReference")
      put("id", "card")
      put("status", "current")
      val hospital = medplum.stored.values.first { it.igId() == "TrustDemoKesaroHospital" }
      put(
        "meta",
        JsonObject(
          hospital.getValue("meta").jsonObject +
            ("lastUpdated" to JsonPrimitive("2026-11-17T09:00:00Z"))
        ),
      )
      put(
        "identifier",
        buildJsonArray {
          add(
            buildJsonObject {
              put("system", "http://smart.who.int/trust-demo/sid/xxr-kesaro-hn")
              put("value", "CRH-HN-68-104377")
            }
          )
        },
      )
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
    }
    val engine =
      StepEngine(
        FhirServer("http://hapi.test/fhir", { null }, emptyHapi),
        medplum.admin,
        Clock.fixed(Instant.parse("2026-11-20T08:00:00Z"), ZoneOffset.UTC),
      )
    assertEquals(resetDay, engine.state().resetDay)
  }
}

private val emptyHapi = MockEngine {
  respond(
    """{"resourceType":"Bundle","type":"searchset"}""",
    headers = headersOf(HttpHeaders.ContentType, "application/fhir+json"),
  )
}
