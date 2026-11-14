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

import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Practitioner
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

class MedplumSeedTest {
  @Test
  fun referencesAreRewrittenToMedplumIds() = runTest {
    val medplum = FakeMedplum()
    medplum.admin.seedBase(ig)
    val clinic = medplum.byIgId<Organization>("TrustDemoKesaroClinic")
    val anan = medplum.byIgId<Practitioner>("TrustDemoAnan")
    assertTrue(medplum.stored.values.none { r -> r.references().any { it.contains("TrustDemo") } })
    assertNotEquals("TrustDemoKesaroClinic", clinic.id)
    assertNotNull(anan.id)
    val roles =
      medplum.stored.values.filter {
        it["resourceType"]?.jsonPrimitive?.content == "PractitionerRole"
      }
    assertTrue(
      roles.all {
        it.references().all { reference -> Regex("[A-Z]\\w+/[\\w-]+").matches(reference) }
      }
    )
  }
}
