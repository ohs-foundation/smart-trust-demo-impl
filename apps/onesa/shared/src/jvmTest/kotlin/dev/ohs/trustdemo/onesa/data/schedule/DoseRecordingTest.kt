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
import dev.ohs.trustdemo.control.Dates
import dev.ohs.trustdemo.control.Step
import dev.ohs.trustdemo.control.expectations
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.verification.EpiDose
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.time.LocalDate as JavaDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DoseRecordingTest {
  private val repository = InMemorySampleFhirRepository()
  private val written = mutableListOf<JsonObject>()
  private val urls = mutableListOf<String>()
  private var ids = 0
  private val engine = MockEngine { request ->
    val bundle = FhirJson.instance.parseToJsonElement((request.body as TextContent).text).jsonObject
    bundle
      .getValue("entry")
      .jsonArray
      .map { it.jsonObject }
      .forEach {
        urls += it.getValue("request").jsonObject.getValue("url").jsonPrimitive.content
        written += it.getValue("resource").jsonObject
      }
    respond(
      """{"resourceType":"Bundle","type":"transaction-response"}""",
      HttpStatusCode.OK,
      headersOf(HttpHeaders.ContentType, "application/fhir+json"),
    )
  }
  private val recording =
    DoseRecording(
      repository = repository,
      choices = { epi.getValue("concept").jsonArray.map { it.jsonObject.dose() } },
      session = { RegistrationSession("token", "chanthason-id", "lomaru-dhc") },
      httpClient = HttpClient(engine),
      baseUrl = "http://gateway/fhir",
      refresh = {
        written.forEach { repository.upsert(decode(it)) }
        true
      },
      caughtUp = { true },
      today = { LocalDate.parse("2026-08-01") },
    )
  private val epi = ig("CodeSystem-TrustDemoOnesaEpiSchedule.json")

  private fun ig(name: String): JsonObject =
    FhirJson.instance.parseToJsonElement(File("../../../ig/resources/$name").readText()).jsonObject

  private fun decode(json: JsonObject): Resource =
    FhirJson.instance.decodeFromJsonElement(Resource.serializer(), json)

  private fun JsonObject.dose() =
    EpiDose(getValue("code").jsonPrimitive.content, getValue("display").jsonPrimitive.content)

  private suspend fun withSyncedReferenceData() {
    repository.upsert(
      decode(
        FhirJson.instance
          .parseToJsonElement(
            """{"resourceType":"Patient","id":"somsak","birthDate":"2025-10-28"}"""
          )
          .jsonObject
      )
    )
    listOf(epi, ig("ConceptMap-TrustDemoProductToOnesaEpi.json")).forEach {
      repository.upsert(decode(it))
    }
  }

  private suspend fun entry(code: String, date: String, lot: String = "XXU-2201"): DoseEntry {
    val dose = recording.doses().single { it.code == code }
    return DoseEntry(
      "somsak",
      dose,
      recording.products(dose).singleOrNull()?.code,
      lot,
      LocalDate.parse(date),
      id = "dose-${++ids}",
    )
  }

  @Test
  fun recordingTheBookedDosesCompletesTheVisit() = runTest {
    withSyncedReferenceData()
    repository.upsert(
      decode(FhirJson.instance.parseToJsonElement(bookedVisitRecommendation).jsonObject)
    )
    repository.upsert(decode(FhirJson.instance.parseToJsonElement(bookedVisitReminder).jsonObject))

    recording.record(entry("XXU-EPI-PENTA-1", "2025-12-31"))
    assertEquals(listOf("Immunization/dose-1", "ImmunizationRecommendation/visit"), urls)
    assertEquals(
      listOf("complete", "due", "due"),
      written.last().getValue("recommendation").jsonArray.map {
        it.jsonObject
          .getValue("forecastStatus")
          .jsonObject
          .getValue("coding")
          .jsonArray
          .single()
          .jsonObject
          .getValue("code")
          .jsonPrimitive
          .content
      },
    )

    recording.record(entry("XXU-EPI-OPV-1", "2025-12-31"))
    recording.record(entry("XXU-EPI-PCV-1", "2025-12-31"))
    assertEquals("Task/reminder", urls.last())
    assertEquals("completed", written.last().getValue("status").jsonPrimitive.content)
    assertEquals(null, BookedVisits(repository).of("somsak"))
    assertEquals(emptyList(), BookedVisits(repository).all())

    urls.clear()
    recording.record(entry("XXU-EPI-MR-1", "2026-07-28"))
    assertEquals(listOf("Immunization/dose-4"), urls)
  }

  @Test
  fun fourRecordedDosesSatisfyTheYearContract() = runTest {
    withSyncedReferenceData()
    listOf(
        "XXU-EPI-PENTA-1" to "2025-12-10",
        "XXU-EPI-OPV-1" to "2025-12-10",
        "XXU-EPI-PCV-1" to "2025-12-10",
        "XXU-EPI-MR-1" to "2026-07-28",
      )
      .forEach { (code, date) ->
        assertIs<RecordOutcome.Recorded>(recording.record(entry(code, date)))
      }

    val penta = written.first()
    assertEquals(listOf("Immunization/dose-1"), urls.take(1))
    assertTrue(urls.none { it.startsWith("urn:uuid") })
    assertEquals(
      listOf("XXU-EPI-PENTA-1", "a3K3X000006MijGUAS"),
      penta.getValue("vaccineCode").jsonObject.getValue("coding").jsonArray.map {
        it.jsonObject.getValue("code").jsonPrimitive.content
      },
    )
    assertEquals("true", penta.getValue("primarySource").jsonPrimitive.content)
    assertEquals(
      "Practitioner/chanthason-id",
      penta
        .getValue("performer")
        .jsonArray
        .single()
        .jsonObject
        .getValue("actor")
        .jsonObject
        .getValue("reference")
        .jsonPrimitive
        .content,
    )
    assertEquals(
      "1",
      penta
        .getValue("protocolApplied")
        .jsonArray
        .single()
        .jsonObject
        .getValue("doseNumberPositiveInt")
        .jsonPrimitive
        .content,
    )
    val dates = Dates(JavaDate.parse("2026-11-14"))
    val contract = expectations.getValue(Step.YEAR).single()
    assertTrue(contract.satisfied(written.map(::decode), dates), contract.label)
  }

  @Test
  fun aRetriedRecordingWritesOneDose() = runTest {
    withSyncedReferenceData()
    val stored = mutableMapOf<String, JsonObject>()
    var requests = 0
    val lossy =
      DoseRecording(
        repository = repository,
        choices = { recording.doses() },
        session = { RegistrationSession("token", "chanthason-id", "lomaru-dhc") },
        httpClient =
          HttpClient(
            MockEngine { request ->
              requests++
              FhirJson.instance
                .parseToJsonElement((request.body as TextContent).text)
                .jsonObject
                .getValue("entry")
                .jsonArray
                .map { it.jsonObject }
                .forEach {
                  val url = it.getValue("request").jsonObject.getValue("url").jsonPrimitive.content
                  stored[url] = it.getValue("resource").jsonObject
                }
              if (requests == 1) throw IOException("the reply was lost")
              respond(
                """{"resourceType":"Bundle","type":"transaction-response"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/fhir+json"),
              )
            }
          ),
        baseUrl = "http://gateway/fhir",
        refresh = { true },
        caughtUp = { true },
        today = { LocalDate.parse("2026-08-01") },
      )
    val entry = entry("XXU-EPI-PENTA-1", "2025-12-10")

    assertFailsWith<IOException> { lossy.record(entry) }
    assertIs<RecordOutcome.Recorded>(lossy.record(entry))

    assertEquals(2, requests)
    assertEquals(listOf("Immunization/${entry.id}"), stored.keys.toList())
  }

  @Test
  fun aStaleCopyAsksForASyncBeforeTheRepeatCheck() = runTest {
    withSyncedReferenceData()
    var caughtUp = true
    val lagging =
      DoseRecording(
        repository = repository,
        choices = { epi.getValue("concept").jsonArray.map { it.jsonObject.dose() } },
        session = { RegistrationSession("token", "chanthason-id", "lomaru-dhc") },
        httpClient = HttpClient(engine),
        baseUrl = "http://gateway/fhir",
        refresh = { error("offline") },
        caughtUp = { caughtUp },
        today = { LocalDate.parse("2026-08-01") },
      )
    val entry = entry("XXU-EPI-PENTA-1", "2025-12-10")

    assertEquals(RecordOutcome.Recorded(listStale = true), lagging.record(entry))
    caughtUp = false

    assertEquals(RecordOutcome.SyncFirst, lagging.record(entry))
    assertEquals(1, written.size)
    assertIs<RecordOutcome.Recorded>(lagging.record(entry, confirmed = true))
  }

  @Test
  fun aDoseNeedsALotAndADateItCouldHaveBeenGiven() = runTest {
    withSyncedReferenceData()

    val refusals =
      listOf(
        entry("XXU-EPI-PENTA-1", "2025-12-10", lot = " ") to DoseProblem.LotNumberMissing,
        entry("XXU-EPI-PENTA-1", "2026-08-02") to DoseProblem.DateInTheFuture,
        entry("XXU-EPI-PENTA-1", "2025-10-27") to DoseProblem.DateBeforeBirth,
      )

    refusals.forEach { (entry, problem) ->
      assertEquals(RecordOutcome.Refused(problem), recording.record(entry))
    }
    assertEquals(emptyList(), written)
    assertIs<RecordOutcome.Recorded>(recording.record(entry("XXU-EPI-PENTA-1", "2026-08-01")))
  }

  @Test
  fun aSecondDoseOfTheSameCodeNeedsConfirmation() = runTest {
    withSyncedReferenceData()
    val first = entry("XXU-EPI-PENTA-1", "2025-12-10")
    recording.record(first)

    val again = recording.record(entry("XXU-EPI-PENTA-1", "2026-01-20"))

    assertEquals(RecordOutcome.AlreadyRecorded(LocalDate.parse("2025-12-10")), again)
    assertEquals(1, written.size)
    assertIs<RecordOutcome.Recorded>(
      recording.record(entry("XXU-EPI-PENTA-1", "2026-01-20"), confirmed = true)
    )
    assertEquals(2, written.size)
    assertNotEquals(urls[0], urls[1])
  }
}
