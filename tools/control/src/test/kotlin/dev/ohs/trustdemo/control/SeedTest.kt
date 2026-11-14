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

import dev.ohs.fhir.model.r4.ConceptMap
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Patient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeedTest {
  @Test
  fun householdIsSeededWithParentsAndNoManager() {
    val group = Seed.onesaBase(ig).resource<Group>("TrustDemoHousehold")
    assertNull(group.managingEntity)
    assertEquals(
      setOf("Patient/TrustDemoBounmyPatient", "Patient/TrustDemoKhamlaPatient"),
      group.memberRefs(),
    )
  }

  @Test
  fun bounmysPatientHasNoLinkYetAndBelongsToLomaru() {
    val bounmy = Seed.onesaBase(ig).resource<Patient>("TrustDemoBounmyPatient")
    assertTrue(bounmy.link.isEmpty())
    assertEquals("Organization/TrustDemoLomaruDhc", bounmy.managingOrganizationRef())
  }

  @Test
  fun conceptMapStartsWithoutHepB() =
    assertNull(
      Seed.onesaBase(ig)
        .resource<ConceptMap>("TrustDemoProductToOnesaEpi")
        .targetFor("vp-hepb-peds-monovalent")
    )

  @Test
  fun noSeededReferencePointsAtAStoryRecord() {
    val bundle = Seed.onesaBase(ig)
    assertTrue(bundle.entry.size > 20)
    val dangling = bundle.allReferences().filterNot { bundle.has(it) }
    assertTrue(dangling.isEmpty(), "references outside the base bundle: $dangling")
  }
}
