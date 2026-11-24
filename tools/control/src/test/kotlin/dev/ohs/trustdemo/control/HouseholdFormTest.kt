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

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HouseholdFormTest {
  @Test
  fun theChwMayPostEveryResourceTheHouseholdFormExtracts() {
    val extracted =
      json(FORM)
        .getValue("contained")
        .jsonArray
        .map { it.jsonObject.getValue("resourceType").jsonPrimitive.content }
        .toSet()
    val chw =
      json(REALM)
        .getValue("groups")
        .jsonArray
        .map { it.jsonObject }
        .single { it.getValue("name").jsonPrimitive.content == "Trust demo CHW" }
        .getValue("realmRoles")
        .jsonArray
        .map { it.jsonPrimitive.content }
        .toSet()

    assertTrue("Patient" in extracted)
    assertEquals(emptySet(), extracted.filterNot { "POST_${it.uppercase()}" in chw }.toSet())
  }

  private fun json(path: String) = Json.parseToJsonElement(File(path).readText()).jsonObject

  private companion object {
    const val FORM =
      "../../apps/onesa/shared/src/commonMain/composeResources/files/configs/Questionnaire-HouseholdRegistration.json"
    const val REALM = "../../stack/onesa/keycloak/onesa-realm.json"
  }
}
