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
package dev.ohs.trustdemo.onesa.data.sync

import dev.ohs.fhir.engine.sync.download.ResourceParamsBasedDownloadWorkManager.TimestampContext
import dev.ohs.fhir.engine.sync.download.UrlDownloadRequest
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.UserContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private object NoTimestamps : TimestampContext {
  override suspend fun saveLastUpdatedTimestamp(resourceType: ResourceType, timestamp: String?) =
    Unit

  override suspend fun getLasUpdateTimestamp(resourceType: ResourceType): String? = null
}

class SyncConfigResolverTest {

  @Test
  fun familySyncAsksOnlyForHouseholdTypes() = runTest {
    val manager =
      RoleDownloadWorkManager(UserContext(AppRole.FAMILY, patientId = "child-1"), NoTimestamps)

    val urls = mutableListOf<String>()
    while (true) {
      urls += (manager.getNextRequest() as? UrlDownloadRequest ?: break).url
    }

    assertEquals(
      listOf(
        "Group",
        "Patient",
        "Immunization",
        "ImmunizationRecommendation",
        "Consent",
        "AuditEvent",
      ),
      urls.map { it.substringBefore('?') },
    )
    assertTrue(urls.all { "_count=" in it }, "$urls")
    val forbidden = listOf("_include", "_revinclude", "_has", "_filter", "_list")
    assertFalse(urls.any { url -> forbidden.any { it in url } }, "$urls")
  }

  @Test
  fun placeholdersAreFilledOrTheEntrySkipped() {
    val config =
      SyncConfig(
        listOf(
          SyncResource("Group", mapOf("member" to "Patient/{patient}")),
          SyncResource("Practitioner", ids = listOf("{practitioner}")),
          SyncResource("Patient"),
        )
      )

    val filled =
      SyncConfigResolver.resolve(
        config,
        UserContext(AppRole.CHW, practitionerId = "p1", patientId = "c1"),
      )
    assertEquals(mapOf("member" to "Patient/c1"), filled[ResourceType.Group])
    assertEquals(mapOf("_id" to "p1"), filled[ResourceType.Practitioner])
    assertEquals(emptyMap(), filled[ResourceType.Patient])

    val skipped = SyncConfigResolver.resolve(config, UserContext(AppRole.FAMILY))
    assertNull(skipped[ResourceType.Group])
    assertNull(skipped[ResourceType.Practitioner])
    assertEquals(emptyMap(), skipped[ResourceType.Patient])
  }
}
