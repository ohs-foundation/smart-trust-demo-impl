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

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.CodeSystem
import dev.ohs.fhir.model.r4.ConceptMap
import dev.ohs.fhir.model.r4.Device
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.UnsignedInt
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.ValueSet
import kotlinx.serialization.json.Json

/** The records that exist before the story starts, as one transaction in reference order. */
object Seed {
  private const val LOMARU = "TrustDemoLomaruDhc"
  private const val PARENT_PATIENTS = "TrustDemoBounmyPatient,TrustDemoKhamlaPatient"
  private const val HEPB_PRODUCT = "a3K3X000006MiiXUAS"
  private const val KEYCLOAK_USER_ID = "http://ohs.dev/identifiers/keycloak-user-id"

  private val keycloakUserIds =
    mapOf(
      "TrustDemoVilay" to "f319bf6e-2b4c-4bda-8e0d-78a7b7a314c9",
      "TrustDemoChanthason" to "0ccf76a7-59ab-4506-969b-44fe92f20b54",
    )

  private val locations =
    listOf(
      """{"resourceType":"Location","id":"TrustDemoLomaruDhcLoc","name":"Lomaru District Health Center",
        "managingOrganization":{"reference":"Organization/TrustDemoLomaruDhc"}}""",
      """{"resourceType":"Location","id":"TrustDemoVillage04","name":"Village 04",
        "partOf":{"reference":"Location/TrustDemoLomaruDhcLoc"}}""",
    )

  private val roles =
    listOf(
      role("TrustDemoVilayRole", "TrustDemoVilay", "TrustDemoVillage04", "chw"),
      role("TrustDemoChanthasonRole", "TrustDemoChanthason", "TrustDemoLomaruDhcLoc", "clinician"),
    )

  fun onesaBase(ig: List<Resource>): Bundle {
    val resources =
      ig.filterIsInstance<CodeSystem>() +
        ig.filterIsInstance<ValueSet>() +
        ig
          .filterIsInstance<ConceptMap>()
          .filter { it.id == "TrustDemoProductToOnesaEpi" }
          .map(::withoutHepB) +
        ig.filterIsInstance<Organization>() +
        locations.map(::parse) +
        ig.filterIsInstance<Practitioner>().map(::withKeycloakUser) +
        roles.map(::parse) +
        ig.filterIsInstance<Device>() +
        ig
          .filterIsInstance<Patient>()
          .filter { it.id in PARENT_PATIENTS.split(',') }
          .map(::parent) +
        ig.filterIsInstance<Group>().filter { it.id == "TrustDemoHousehold" }.map(::withParentsOnly)
    return Bundle(
      type = Enumeration(value = Bundle.BundleType.Transaction),
      entry = resources.map(::putEntry),
    )
  }

  private fun putEntry(resource: Resource) =
    Bundle.Entry(
      resource = resource,
      request =
        Bundle.Entry.Request(
          method = Enumeration(value = Bundle.HTTPVerb.Put),
          url = Uri(value = "${resource.resourceType()}/${resource.id}"),
        ),
    )

  private fun withoutHepB(map: ConceptMap) =
    map.copy(
      group =
        map.group.map { group ->
          group.copy(element = group.element.filter { it.code?.value != HEPB_PRODUCT })
        }
    )

  private fun withKeycloakUser(practitioner: Practitioner) =
    keycloakUserIds[practitioner.id]?.let { userId ->
      practitioner.copy(
        identifier =
          practitioner.identifier +
            Identifier(system = Uri(value = KEYCLOAK_USER_ID), value = FhirString(value = userId))
      )
    } ?: practitioner

  private fun parent(patient: Patient) =
    patient.copy(link = emptyList(), managingOrganization = reference("Organization/$LOMARU"))

  private fun withParentsOnly(group: Group): Group {
    val parents =
      group.member.filter {
        it.entity.reference?.value?.removePrefix("Patient/") in PARENT_PATIENTS.split(',')
      }
    return group.copy(
      managingEntity = null,
      member = parents,
      quantity = UnsignedInt(value = parents.size),
    )
  }

  private fun reference(target: String) = Reference(reference = FhirString(value = target))

  private fun role(id: String, practitioner: String, location: String, code: String) =
    """{"resourceType":"PractitionerRole","id":"$id",
      "practitioner":{"reference":"Practitioner/$practitioner"},
      "organization":{"reference":"Organization/$LOMARU"},
      "location":[{"reference":"Location/$location"}],
      "code":[{"coding":[{"system":"http://ohs.dev/fhir/CodeSystem/app-role","code":"$code"}]}]}"""

  private fun parse(json: String): Resource = Json.decodeFromString(Resource.serializer(), json)
}
