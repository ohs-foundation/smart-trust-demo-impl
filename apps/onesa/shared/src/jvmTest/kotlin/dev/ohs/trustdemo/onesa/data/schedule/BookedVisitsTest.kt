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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class BookedVisitsTest {
  private val repository = InMemorySampleFhirRepository()
  private val visits = BookedVisits(repository)

  private suspend fun upsert(json: String) {
    repository.upsert(FhirJson.instance.decodeFromString(Resource.serializer(), json))
  }

  private suspend fun bookedFor(vararg given: Triple<String, String, String>) {
    upsert("""{"resourceType":"Patient","id":"somsak","birthDate":"2025-10-28"}""")
    upsert(bookedVisitRecommendation)
    upsert(bookedVisitReminder)
    given.forEach { (id, code, date) ->
      upsert(
        """{"resourceType":"Immunization","id":"$id","status":"completed","primarySource":true,
        "patient":{"reference":"Patient/somsak"},"occurrenceDateTime":"$date",
        "vaccineCode":{"coding":[{"system":"${ScanTransactions.EPI_SYSTEM}","code":"$code"}]}}"""
      )
    }
  }

  @Test
  fun aVisitWhoseDosesWereAllGivenIsNoLongerBooked() = runTest {
    bookedFor(*yearOfDoses().toTypedArray())

    assertNull(visits.of("somsak"))
    assertEquals(emptyList(), visits.all())
  }

  @Test
  fun aDoseGivenSinceLeavesTheVisit() = runTest {
    bookedFor(yearOfDoses().single { it.first == "penta" })

    assertEquals(
      listOf("XXU-EPI-OPV-1", "XXU-EPI-PCV-1"),
      visits.of("somsak")?.doses?.map { it.code },
    )
    assertEquals("reminder", visits.all().single().visit.reminderId)
  }
}
