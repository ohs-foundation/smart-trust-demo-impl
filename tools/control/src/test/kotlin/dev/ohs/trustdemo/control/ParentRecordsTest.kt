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

import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ParentRecordsTest {
  private val today = LocalDate.parse("2026-11-24")
  private val records =
    Seed.parentRecords(today).entry.map {
      Json.encodeToJsonElement(Resource.serializer(), it.resource!!).jsonObject
    }

  private fun JsonObject.owner() =
    (this["patient"] ?: this["subject"])!!.jsonObject.getValue("reference").jsonPrimitive.content

  @Test
  fun everyRecordBelongsToAParentTheBaseSeeds() {
    val parents =
      Seed.onesaBase(ig).entry.mapNotNull { it.resource as? Patient }.map { "Patient/${it.id}" }

    assertEquals(2, parents.size)
    assertEquals(parents.toSet(), records.map { it.owner() }.toSet())
  }

  @Test
  fun theMothersAnaemiaStartsAfterSomsaksBirth() {
    val anaemia =
      records.single {
        it.getValue("resourceType").jsonPrimitive.content == "Condition" &&
          it.owner() == "Patient/TrustDemoKhamlaPatient"
      }
    val onset = LocalDate.parse(anaemia.getValue("onsetDateTime").jsonPrimitive.content.take(10))

    assertTrue(onset.isAfter(Dates(today).birthDate))
    assertTrue(onset.isBefore(today))
  }
}
