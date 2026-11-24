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
package dev.ohs.trustdemo.onesa.data.schedule

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.verification.ScanTransactions
import dev.ohs.trustdemo.onesa.util.FhirJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PROTOCOLS = "src/commonMain/composeResources/files/protocols"
private const val INFANT = "PlanDefinition-TrustDemoOnesaInfantSchedule.json"
private const val BIRTH = "2025-10-28"

class ScheduleTest {
  private val repository = InMemorySampleFhirRepository()
  private val plans =
    SchedulePlans.parse(File("$PROTOCOLS/PlanDefinition-TrustDemoDT03.json").readText(), infant())
  private val service = ScheduleService(repository, { plans }) { LocalDate.parse("2025-11-14") }

  private fun infant(): String = File("$PROTOCOLS/$INFANT").readText()

  private suspend fun child(birthDate: String = BIRTH) =
    upsert("""{"resourceType":"Patient","id":"somsak","birthDate":"$birthDate"}""")

  private suspend fun upsert(json: String) {
    repository.upsert(FhirJson.instance.decodeFromString(Resource.serializer(), json))
  }

  private suspend fun dose(
    id: String,
    primarySource: Boolean,
    vararg codings: Pair<String, String>,
    date: String = "2025-10-28",
    status: String = "completed",
  ) {
    val coding = codings.joinToString(",") { """{"system":"${it.first}","code":"${it.second}"}""" }
    upsert(
      """{"resourceType":"Immunization","id":"$id","status":"$status","primarySource":$primarySource,
      "patient":{"reference":"Patient/somsak"},"occurrenceDateTime":"$date",
      "vaccineCode":{"coding":[$coding]}}"""
    )
  }

  private val product = ScanTransactions.PRODUCT_SYSTEM to "vp-hepb-peds-monovalent"
  private val hepB = ScanTransactions.EPI_SYSTEM to "XXU-EPI-HEPB-BD"
  private val bcg = ScanTransactions.EPI_SYSTEM to "XXU-EPI-BCG"

  private suspend fun standing(id: String) =
    service.evaluate("somsak").items.single { it.id == id }.standing

  @Test
  fun dt03Rule1AReportedDoseIsNotCounted() = runTest {
    child()
    dose("reported", false, product, hepB)

    val schedule = service.evaluate("somsak")

    assertEquals(listOf("reported"), schedule.reported.map { it.id })
    assertEquals(emptyList(), schedule.counted)
    assertEquals(Standing.Due(LocalDate.parse(BIRTH)), standing("hepb-birth"))
  }

  @Test
  fun dt03Rule2AVerifiedDoseWithNoScheduleCodeIsUnmapped() = runTest {
    child()
    dose("unmapped", true, product)

    val schedule = service.evaluate("somsak")

    assertEquals(listOf("unmapped"), schedule.unmapped.map { it.id })
    assertEquals(emptyList(), schedule.counted)
  }

  @Test
  fun dt03Rule3AVerifiedDoseWithAScheduleCodeIsCounted() = runTest {
    child()
    dose("counted", true, hepB, product)

    val schedule = service.evaluate("somsak")

    assertEquals(listOf("counted"), schedule.counted.map { it.id })
    assertEquals(Standing.Complete, standing("hepb-birth"))
  }

  @Test
  fun dt04Rule1ACountedDoseIsComplete() = runTest {
    child()
    dose("hepb", true, hepB)
    dose("bcg", true, bcg)

    val items = service.evaluate("somsak").items

    assertEquals(listOf(Standing.Complete, Standing.Complete), items.take(2).map { it.standing })
  }

  @Test
  fun dt04Rule2TheNextVisitIsDueAtBirthDatePlusTimingAge() = runTest {
    child()
    dose("hepb", true, hepB)
    dose("bcg", true, bcg)

    val due = service.evaluate("somsak").items.filter { it.standing is Standing.Due }

    assertEquals(listOf("penta-1", "opv-1", "pcv-1"), due.map { it.id })
    assertEquals(
      setOf<Standing>(Standing.Due(LocalDate.parse("2025-12-09"))),
      due.map { it.standing }.toSet(),
    )
  }

  @Test
  fun dt04Rule3ALaterVisitIsNotYetDue() = runTest {
    child()
    dose("hepb", true, hepB)
    dose("bcg", true, bcg)

    assertEquals(Standing.NotYet(LocalDate.parse("2026-07-28")), standing("mr-1"))
  }

  @Test
  fun aMalformedConditionFailsOnlyItsAction() = runTest {
    child()
    dose("hepb", true, hepB)
    dose("bcg", true, bcg)
    val plans =
      SchedulePlans.parse(
        File("$PROTOCOLS/PlanDefinition-TrustDemoDT03.json").readText(),
        broken("opv-1"),
      )

    val items =
      ScheduleService(repository, { plans }) { LocalDate.parse("2025-11-14") }
        .evaluate("somsak")
        .items

    assertEquals(
      listOf("Complete", "Complete", "Due", "CouldNotEvaluate", "Due", "NotYet"),
      items.map { it.standing::class.simpleName },
    )
  }

  /** The IG schedule with the due condition of [actionId] cut off mid-expression. */
  private fun broken(actionId: String): String {
    val plan = plansJson()
    val actions =
      plan.getValue("action").jsonArray.map { action ->
        if (action.jsonObject.getValue("id").jsonPrimitive.content != actionId) action
        else {
          val children =
            action.jsonObject.getValue("action").jsonArray.map { child ->
              if (child.jsonObject.getValue("id").jsonPrimitive.content != "due") child
              else
                FhirJson.instance.parseToJsonElement(
                  child
                    .toString()
                    .replace(
                      "(%counted.coding.code contains %code).not() and",
                      "(%counted.coding.code contains and",
                    )
                )
            }
          JsonObject(action.jsonObject + ("action" to JsonArray(children)))
        }
      }
    return JsonObject(plan + ("action" to JsonArray(actions))).toString()
  }

  @Test
  fun aMissedDoseIsDueWithTheNextVisitsDoses() = runTest {
    child()
    dose("hepb", true, hepB)

    val items = service.evaluate("somsak").items

    assertEquals(
      listOf(
        Standing.Complete,
        Standing.Due(LocalDate.parse(BIRTH)),
        Standing.Due(LocalDate.parse("2025-12-09")),
        Standing.Due(LocalDate.parse("2025-12-09")),
        Standing.Due(LocalDate.parse("2025-12-09")),
        Standing.NotYet(LocalDate.parse("2026-07-28")),
      ),
      items.map { it.standing },
    )
  }

  @Test
  fun aFailedActionNeverDecidesTheNextVisit() = runTest {
    child()
    dose("bcg", true, bcg)
    val plans =
      SchedulePlans.parse(
        File("$PROTOCOLS/PlanDefinition-TrustDemoDT03.json").readText(),
        broken("hepb-birth"),
      )

    val items =
      ScheduleService(repository, { plans }) { LocalDate.parse("2025-11-14") }
        .evaluate("somsak")
        .items

    assertEquals(
      listOf("CouldNotEvaluate", "Complete", "Due", "Due", "Due", "NotYet"),
      items.map { it.standing::class.simpleName },
    )
  }

  @Test
  fun anActionWithoutAnAgeFailsAlone() = runTest {
    child()
    dose("hepb", true, hepB)
    dose("bcg", true, bcg)
    val plans =
      SchedulePlans.parse(
        File("$PROTOCOLS/PlanDefinition-TrustDemoDT03.json").readText(),
        withoutAge("mr-1"),
      )

    val items =
      ScheduleService(repository, { plans }) { LocalDate.parse("2025-11-14") }
        .evaluate("somsak")
        .items

    assertEquals(
      listOf("Complete", "Complete", "Due", "Due", "Due", "CouldNotEvaluate"),
      items.map { it.standing::class.simpleName },
    )
  }

  @Test
  fun aDoseEnteredInErrorNeverCounts() = runTest {
    child()
    dose("bad", true, hepB, status = "entered-in-error")

    val schedule = service.evaluate("somsak")

    assertEquals(emptyList(), schedule.counted)
    assertEquals(Standing.Due(LocalDate.parse(BIRTH)), standing("hepb-birth"))
    assertEquals(emptyList(), schedule.unreadable)
  }

  @Test
  fun aDoseWithNoPrimarySourceIsReportedNotVerified() = runTest {
    child()
    upsert(
      """{"resourceType":"Immunization","id":"unknown","status":"completed",
      "patient":{"reference":"Patient/somsak"},"occurrenceDateTime":"2025-10-28",
      "vaccineCode":{"coding":[{"system":"${hepB.first}","code":"${hepB.second}"}]}}"""
    )

    val schedule = service.evaluate("somsak")

    assertEquals(listOf("unknown"), schedule.reported.map { it.id })
    assertEquals(emptyList(), schedule.counted)
  }

  /** The IG schedule with the age of [actionId] removed. */
  private fun withoutAge(actionId: String): String {
    val plan = plansJson()
    val actions =
      plan.getValue("action").jsonArray.map { action ->
        if (action.jsonObject.getValue("id").jsonPrimitive.content == actionId)
          JsonObject(action.jsonObject - "timingAge")
        else action
      }
    return JsonObject(plan + ("action" to JsonArray(actions))).toString()
  }

  @Test
  fun aYearOfDosesGivenAfterTheBookedVisitIsAllVerified() = runTest {
    val service = ScheduleService(repository, { plans }) { LocalDate.parse("2026-11-18") }
    child("2025-11-18")
    yearOfDoses().forEach { (id, code, date) ->
      dose(id, true, ScanTransactions.EPI_SYSTEM to code, date = date)
    }
    upsert(bookedVisitRecommendation)
    upsert(bookedVisitReminder)

    val items = service.evaluate("somsak").items

    assertEquals(6, items.size)
    assertEquals(List(6) { Standing.Complete }, items.map { it.standing })
  }

  @Test
  fun theExecutableScheduleKeepsTheIgActions() {
    val ours = plansJson().getValue("action").jsonArray.map { it.jsonObject }
    val ig =
      FhirJson.instance
        .parseToJsonElement(File("../../../ig/resources/$INFANT").readText())
        .jsonObject
        .getValue("action")
        .jsonArray
        .map { it.jsonObject }

    assertEquals(6, ig.size)
    assertEquals(
      ig.map { listOf(it["title"], it["code"], it["timingAge"]) },
      ours.map { listOf(it["title"], it["code"], it["timingAge"]) },
    )
  }

  private fun plansJson(): JsonObject = FhirJson.instance.parseToJsonElement(infant()).jsonObject
}
