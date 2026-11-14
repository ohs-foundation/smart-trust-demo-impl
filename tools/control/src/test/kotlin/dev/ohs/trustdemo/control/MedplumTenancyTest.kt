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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val TENANT_TYPES = listOf("Patient", "Immunization", "Provenance", "DocumentReference")

class MedplumTenancyTest {
  private val medplum = FakeMedplum()

  private suspend fun seeded() {
    medplum.admin.seedBase(ig)
    medplum.admin.ensureClinician("password")
  }

  private fun FakeMedplum.records(type: String) =
    stored.entries.filter { it.key.startsWith("$type/") }.map { it.value }

  private fun FakeMedplum.organizationId(igId: String) =
    stored.entries
      .single { it.key.startsWith("Organization/") && it.value.igId() == igId }
      .value
      .getValue("id")
      .jsonPrimitive
      .content

  private fun JsonObject.accounts() =
    this["meta"]?.jsonObject?.get("accounts")?.jsonArray.orEmpty().map {
      it.jsonObject.getValue("reference").jsonPrimitive.content
    }

  @Test
  fun clinicalRecordsAreScopedToTheOrganisationParameter() = runTest {
    seeded()
    val rules =
      medplum.records("AccessPolicy").single().getValue("resource").jsonArray.map { it.jsonObject }
    TENANT_TYPES.forEach { type ->
      val rule = rules.single { it.getValue("resourceType").jsonPrimitive.content == type }
      assertEquals("$type?_compartment=%organization", rule["criteria"]?.jsonPrimitive?.content)
    }
    assertTrue(rules.none { it["resourceType"]?.jsonPrimitive?.content == "Binary" })
  }

  @Test
  fun ananHasOneAccessEntryPerKesaroOrganisation() = runTest {
    seeded()
    val membership = medplum.records("ProjectMembership").single()
    val policy = medplum.stored.keys.single { it.startsWith("AccessPolicy/") }
    assertNull(membership["accessPolicy"])
    val access = membership.getValue("access").jsonArray.map { it.jsonObject }
    assertEquals(
      listOf(policy, policy),
      access.map { it.getValue("policy").jsonObject.getValue("reference").jsonPrimitive.content },
    )
    assertEquals(
      setOf(
        "Organization/${medplum.organizationId("TrustDemoKesaroHospital")}",
        "Organization/${medplum.organizationId("TrustDemoKesaroClinic")}",
      ),
      access
        .map { entry ->
          val parameter = entry.getValue("parameter").jsonArray.single().jsonObject
          assertEquals("organization", parameter.getValue("name").jsonPrimitive.content)
          parameter
            .getValue("valueReference")
            .jsonObject
            .getValue("reference")
            .jsonPrimitive
            .content
        }
        .toSet(),
    )
  }

  @Test
  fun membershipThatPredatesTenancyIsRewrittenNotDuplicated() = runTest {
    seeded()
    val key = medplum.stored.keys.single { it.startsWith("ProjectMembership/") }
    medplum.stored[key] = JsonObject(medplum.stored.getValue(key) - "access")
    medplum.admin.ensureClinician("password")
    assertEquals(1, medplum.records("ProjectMembership").size)
    assertEquals(2, medplum.records("ProjectMembership").single().getValue("access").jsonArray.size)
  }

  @Test
  fun baseRecordsAndCardsAreWrittenIntoTheirOrganisationsCompartment() = runTest {
    seeded()
    medplum.admin.uploadCard("urn:hn", "CRH-HN-68-104377", "TrustDemoJ1HepB", "HC1:X")
    val hospital = "Organization/${medplum.organizationId("TrustDemoKesaroHospital")}"
    val clinic = "Organization/${medplum.organizationId("TrustDemoKesaroClinic")}"
    assertEquals(listOf(hospital), medplum.records("DocumentReference").single().accounts())
    assertEquals(listOf(hospital), medplum.records("Location").single().accounts())
    assertEquals(
      setOf(hospital, clinic),
      medplum.records("PractitionerRole").flatMap { it.accounts() }.toSet(),
    )
    assertEquals(listOf(clinic), medplum.records("Device").single().accounts())
  }

  @Test
  fun theFacilityClientIsOnePublicApplicationWhoseRedirectIsItsOrigin() = runTest {
    medplum.admin.seedBase(ig)
    val first = medplum.admin.ensureClientApplication("http://client.test:8480")
    val second = medplum.admin.ensureClientApplication("http://client.test:8480")
    assertEquals(first, second)
    val app = medplum.records("ClientApplication").single()
    assertEquals("taifafeki-facility-client", app["name"]?.jsonPrimitive?.contentOrNull)
    assertEquals("http://client.test:8480", app["redirectUri"]?.jsonPrimitive?.contentOrNull)
  }
}
