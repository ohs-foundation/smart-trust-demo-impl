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

class SyncRolesTest {
  @Test
  fun eachRoleMayReadEveryTypeItsPhoneSyncs() {
    val realm = json(REALM)
    val groups =
      realm.getValue("groups").jsonArray.associate { group ->
        group.jsonObject.getValue("name").jsonPrimitive.content to
          group.jsonObject.getValue("realmRoles").jsonArray.map { it.jsonPrimitive.content }.toSet()
      }
    val unreadable =
      ROLES.flatMap { (config, group) ->
        val synced =
          json("$SYNC/$config.json").getValue("resources").jsonArray.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
          }
        assertTrue(synced.isNotEmpty())
        synced
          .filterNot { "GET_${it.uppercase()}" in groups.getValue(group) }
          .map { "$config: $it" }
      }

    assertEquals(emptyList(), unreadable)
  }

  private fun json(path: String) = Json.parseToJsonElement(File(path).readText()).jsonObject

  private companion object {
    const val REALM = "../../stack/onesa/keycloak/onesa-realm.json"
    const val SYNC = "../../apps/onesa/shared/src/commonMain/composeResources/files/sync"
    val ROLES =
      mapOf(
        "chw" to "Trust demo CHW",
        "clinician" to "Trust demo clinician",
        "family" to "Trust demo family",
      )
  }
}
