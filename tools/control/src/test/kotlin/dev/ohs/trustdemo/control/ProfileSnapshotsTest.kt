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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProfileSnapshotsTest {
  private val snapshots = ProfileSnapshots { url ->
    baseDefinitions.single { it.getValue("url").jsonPrimitive.content == url }
  }

  private suspend fun snapshotOf(name: String): List<JsonObject> {
    val resource =
      checkNotNull(ProfileSnapshotsTest::class.java.classLoader)
        .getResourceAsStream("StructureDefinition-TrustDemoTaifafeki$name.json")!!
        .use { it.reader().readText() }
    val profile = kotlinx.serialization.json.Json.parseToJsonElement(resource).jsonObject
    return snapshots
      .withSnapshot(profile)
      .getValue("snapshot")
      .jsonObject
      .getValue("element")
      .jsonArray
      .map { it.jsonObject }
  }

  private fun List<JsonObject>.element(id: String) = single {
    it.getValue("id").jsonPrimitive.content == id
  }

  @Test
  fun aConstrainedElementKeepsItsBaseAndTakesTheProfilesCardinality() = runTest {
    val patient = snapshotOf("Patient")
    assertEquals("1", patient.element("Patient.birthDate").getValue("min").jsonPrimitive.content)
    assertEquals("0", patient.element("Patient.gender").getValue("min").jsonPrimitive.content)
  }

  @Test
  fun aSliceGetsItsOwnElementsAndTheDatatypesChildren() = runTest {
    val patient = snapshotOf("Patient")
    val ids = patient.map { it.getValue("id").jsonPrimitive.content }
    assertEquals(
      listOf("Patient.identifier", "Patient.identifier:hn"),
      ids.filter { it.startsWith("Patient.identifier") && '.' !in it.removePrefix("Patient.") },
    )
    assertEquals(
      "http://smart.who.int/trust-demo/sid/xxr-kesaro-hn",
      patient.element("Patient.identifier:hn.system").getValue("patternUri").jsonPrimitive.content,
    )
  }

  @Test
  fun elementsOfADatatypeAreExpandedBeforeTheyAreConstrained() = runTest {
    val immunization = snapshotOf("Immunization")
    assertEquals(
      "1",
      immunization
        .element("Immunization.vaccineCode.coding:product.system")
        .getValue("min")
        .jsonPrimitive
        .content,
    )
    immunization.element("Immunization.vaccineCode.coding:cvx")
  }
}
