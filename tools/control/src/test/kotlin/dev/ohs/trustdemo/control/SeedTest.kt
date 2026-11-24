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
import dev.ohs.fhir.model.r4.Practitioner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        .targetFor("a3K3X000006MiiXUAS")
    )

  @Test
  fun noSeededReferencePointsAtAStoryRecord() {
    val bundle = Seed.onesaBase(ig)
    assertTrue(bundle.entry.size > 20)
    val dangling = bundle.allReferences().filterNot { bundle.has(it) }
    assertTrue(dangling.isEmpty(), "references outside the base bundle: $dangling")
  }

  @Test
  fun staffPractitionersCarryTheirKeycloakUserIds() {
    val realm = Json.parseToJsonElement(File(REALM).readText()).jsonObject
    val staff =
      realm
        .getValue("users")
        .jsonArray
        .map { it.jsonObject }
        .filter { fhirUser(it)?.startsWith("Practitioner/") == true }
    assertEquals(
      setOf("vilay", "chanthason"),
      staff.map { it.getValue("username").jsonPrimitive.content }.toSet(),
    )
    val base = Seed.onesaBase(ig)
    staff.forEach { user ->
      val practitioner = base.resource<Practitioner>(fhirUser(user)!!.removePrefix("Practitioner/"))
      val linked = practitioner.identifier.single { it.system?.value == KEYCLOAK_USER_ID }
      assertEquals(
        user.getValue("id").jsonPrimitive.content,
        linked.value?.value,
        user.getValue("username").jsonPrimitive.content,
      )
    }
  }

  @Test
  fun theCliServiceAccountHoldsOnlyWhatFamilyAddNeeds() {
    val realm = Json.parseToJsonElement(File(REALM).readText()).jsonObject
    val client =
      realm
        .getValue("clients")
        .jsonArray
        .map { it.jsonObject }
        .single { it.getValue("clientId").jsonPrimitive.content == "trust-demo-cli" }
    assertEquals("true", client.getValue("serviceAccountsEnabled").jsonPrimitive.content)
    val account =
      realm
        .getValue("users")
        .jsonArray
        .map { it.jsonObject }
        .single {
          it.getValue("username").jsonPrimitive.content == "service-account-trust-demo-cli"
        }
    val roles =
      account.getValue("clientRoles").jsonObject.getValue("realm-management").jsonArray.map {
        it.jsonPrimitive.content
      }
    assertEquals(setOf("manage-users", "view-users"), roles.toSet())
    assertEquals(2, roles.size)
  }

  private fun fhirUser(user: JsonObject) =
    user["attributes"]?.jsonObject?.get("fhirUser")?.jsonArray?.single()?.jsonPrimitive?.content

  private companion object {
    const val REALM = "../../stack/onesa/keycloak/onesa-realm.json"
    const val KEYCLOAK_USER_ID = "http://ohs.dev/identifiers/keycloak-user-id"
  }
}
