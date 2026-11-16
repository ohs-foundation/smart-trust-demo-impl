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

/** The gateway's audited actions against what `watch` and the apps' AuditEvent syncs ask for. */
class AuditActionsTest {

  @Test
  fun watchAndTheSyncsLeaveOutOnlyTheGatewaysReadAudits() {
    val audited = COMPOSE_FILES.map { auditedActions(File(REPO, it)) }.distinct()
    assertEquals(1, audited.size, "the compose files audit different actions: $audited")
    val actions = audited.single()
    assertTrue("R" in actions, "the gateway no longer audits reads: $actions")
    assertTrue(
      "E" !in actions,
      "BALP query audits carry E, as the apps' scan audits and the VHL service's accesses do, " +
        "so no action filter can leave them out",
    )
    val followed = actions - "R" + "E"

    assertEquals(followed, WATCH_CRITERIA.substringAfter("AuditEvent?action=").split(',').toSet())

    val syncs =
      File(REPO, SYNC_DIR)
        .listFiles { file -> file.extension == "json" }
        .orEmpty()
        .flatMap { file ->
          Json.parseToJsonElement(file.readText())
            .jsonObject
            .getValue("resources")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "AuditEvent" }
            .map { file.name to it["params"]?.jsonObject?.get("action")?.jsonPrimitive?.content }
        }
    assertTrue(syncs.isNotEmpty(), "no role syncs AuditEvents under $SYNC_DIR")
    syncs.forEach { (role, action) ->
      assertEquals(followed, action?.split(',')?.toSet(), "$role's AuditEvent action filter")
    }
  }

  private fun auditedActions(compose: File): Set<String> {
    val found = AUDIT_CONFIG.findAll(compose.readText()).map { it.groupValues[1] }.toList()
    assertEquals(1, found.size, "${compose.name} should set AUDIT_EVENT_ACTIONS_CONFIG once")
    return found.single().map(Char::toString).toSet()
  }

  private companion object {
    val REPO = File("../..")
    val COMPOSE_FILES = listOf("stack/onesa/compose.yaml", "infra/docker-compose.yaml")
    const val SYNC_DIR = "apps/onesa/shared/src/commonMain/composeResources/files/sync"
    val AUDIT_CONFIG = Regex("""AUDIT_EVENT_ACTIONS_CONFIG[:=]\s*([A-Z]+)""")
  }
}
