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
package dev.ohs.trustdemo.onesa.data.verification

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.schedule.SchedulePlans
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleService
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/** S5 proposes the national dose of the product's antigens due at the child's age when given. */
class DoseProposalTest {
  private val repository =
    InMemorySampleFhirRepository().apply {
      runBlocking {
        upsert(ig("Patient-TrustDemoSomsak"))
        upsert(ig("CodeSystem-TrustDemoOnesaEpiSchedule"))
      }
    }
  private val matching =
    DoseMatchService(repository, { null }, HttpClient(MockEngine { error("") }), "", { true })
  private val schedule = runBlocking {
    ScheduleService(repository, { plans() }) { LocalDate.parse("2026-01-06") }
      .evaluate("TrustDemoSomsak")
  }
  private val choices = runBlocking { matching.choices() }

  private fun proposed(product: String, given: String): String? = runBlocking {
    val dose = UnmatchedDose("dose", "TrustDemoSomsak", product, "", LocalDate.parse(given))
    matching.proposal(dose, choices, schedule)?.code
  }

  @Test
  fun hepBGivenAtBirthIsTheBirthDose() {
    assertEquals("XXU-EPI-HEPB-BD", proposed("a3K3X000006MiiXUAS", "2025-10-28"))
    assertEquals("XXU-EPI-HEPB-BD", proposed("a3K3X000006MiiXUAS", "2025-10-29"))
  }

  @Test
  fun eachProductGivenAtItsAgeIsItsScheduleDose() {
    assertEquals("XXU-EPI-BCG", proposed("a3K3X000006MiisUAC", "2025-10-28"))
    assertEquals("XXU-EPI-PENTA-1", proposed("a3K3X000006MijGUAS", "2026-01-06"))
    assertEquals("XXU-EPI-OPV-1", proposed("a3K3X000006MijBUAS", "2026-01-06"))
    assertEquals("XXU-EPI-PCV-1", proposed("a3K3X000006MijJUAS", "2026-01-06"))
    assertEquals("XXU-EPI-MR-1", proposed("a3K3X000006MiimUAC", "2026-07-28"))
  }

  @Test
  fun aDoseGivenAtTheWrongAgeIsNoScheduleDose() {
    assertNull(proposed("a3K3X000006MiiXUAS", "2026-01-06"))
    assertNull(proposed("a3K3X000006MijGUAS", "2025-10-28"))
    assertNull(proposed("a3K3X000006MiimUAC", "2026-01-06"))
  }

  @Test
  fun aProductOfUnknownAntigensIsNoScheduleDose() {
    assertNull(proposed("XM9V38", "2026-07-28"))
  }

  private fun ig(name: String): Resource =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      File("../../../ig/resources/$name.json").readText(),
    )

  private fun plans(): SchedulePlans {
    val protocols = "src/commonMain/composeResources/files/protocols"
    return SchedulePlans.parse(
      File("$protocols/PlanDefinition-TrustDemoDT03.json").readText(),
      File("$protocols/PlanDefinition-TrustDemoOnesaInfantSchedule.json").readText(),
    )
  }
}
