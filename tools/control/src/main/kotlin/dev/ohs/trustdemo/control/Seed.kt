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
import java.time.LocalDate
import kotlinx.serialization.json.Json

/** The records that exist before the story starts, as one transaction in reference order. */
object Seed {
  private const val LOMARU = "TrustDemoLomaruDhc"
  private const val PARENT_PATIENTS = "TrustDemoBounmyPatient,TrustDemoKhamlaPatient"
  private const val HEPB_PRODUCT = "a3K3X000006MiiXUAS"
  private const val KEYCLOAK_USER_ID = "http://ohs.dev/identifiers/keycloak-user-id"
  private const val BOUNMY = "TrustDemoBounmyPatient"
  private const val KHAMLA = "TrustDemoKhamlaPatient"
  private const val SNOMED = "http://snomed.info/sct"
  private const val LOINC = "http://loinc.org"
  private const val ANAEMIA_AFTER_BIRTH_DAYS = 14L
  private const val BLOOD_PRESSURE_DAYS_AGO = 30L

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

  /**
   * The parents' own health records, so a summary they share carries more than immunizations. Not
   * in the IG (owner, 24 Nov): Bounmy has hypertension and type 2 diabetes, Khamla anaemia since
   * Somsak's birth; the dates follow [today].
   */
  fun parentRecords(today: LocalDate): Bundle {
    val anaemiaSince = Dates(today).birthDate.plusDays(ANAEMIA_AFTER_BIRTH_DAYS)
    val bloodPressureOn = today.minusDays(BLOOD_PRESSURE_DAYS_AGO)
    val records =
      listOf(
        condition(
          "TrustDemoBounmyHypertension",
          BOUNMY,
          "59621000",
          "Essential hypertension",
          "2021-03-10",
        ),
        condition(
          "TrustDemoBounmyDiabetes",
          BOUNMY,
          "44054006",
          "Diabetes mellitus type 2",
          "2023-06-02",
        ),
        medication(
          "TrustDemoBounmyAmlodipine",
          BOUNMY,
          "C08CA01",
          "amlodipine",
          "5 mg by mouth once daily",
          "2021-03-10",
        ),
        medication(
          "TrustDemoBounmyMetformin",
          BOUNMY,
          "A10BA02",
          "metformin",
          "500 mg by mouth twice daily",
          "2023-06-02",
        ),
        allergy("TrustDemoBounmyNoKnownAllergy", BOUNMY, "716186003", "No known allergy", null),
        """{"resourceType":"Observation","id":"TrustDemoBounmyBloodPressure","status":"final",
          "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"vital-signs","display":"Vital Signs"}]}],
          "code":{"coding":[{"system":"$LOINC","code":"85354-9","display":"Blood pressure panel with all children optional"}],"text":"Blood pressure"},
          "subject":{"reference":"Patient/$BOUNMY"},"effectiveDateTime":"$bloodPressureOn",
          "component":[${pressure("8480-6", "Systolic blood pressure", 148)},${pressure("8462-4", "Diastolic blood pressure", 94)}]}""",
        condition(
          "TrustDemoKhamlaAnaemia",
          KHAMLA,
          "87522002",
          "Iron deficiency anaemia",
          "$anaemiaSince",
        ),
        medication(
          "TrustDemoKhamlaFerrousSulfate",
          KHAMLA,
          "B03AA07",
          "ferrous sulfate",
          "200 mg by mouth once daily",
          "$anaemiaSince",
        ),
        allergy(
          "TrustDemoKhamlaPenicillinAllergy",
          KHAMLA,
          "91936005",
          "Allergy to penicillin",
          "271807003" to "Eruption of skin",
        ),
      )
    return Bundle(
      type = Enumeration(value = Bundle.BundleType.Transaction),
      entry = records.map(::parse).map(::putEntry),
    )
  }

  private fun condition(id: String, patient: String, code: String, display: String, onset: String) =
    """{"resourceType":"Condition","id":"$id",
      "clinicalStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/condition-clinical","code":"active"}]},
      "verificationStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/condition-ver-status","code":"confirmed"}]},
      "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/condition-category","code":"problem-list-item"}]}],
      "code":{"coding":[{"system":"$SNOMED","code":"$code","display":"$display"}],"text":"$display"},
      "subject":{"reference":"Patient/$patient"},"onsetDateTime":"$onset"}"""

  private fun medication(
    id: String,
    patient: String,
    atc: String,
    display: String,
    dose: String,
    since: String,
  ) =
    """{"resourceType":"MedicationStatement","id":"$id","status":"active",
      "medicationCodeableConcept":{"coding":[{"system":"http://www.whocc.no/atc","code":"$atc","display":"$display"}],"text":"$display"},
      "subject":{"reference":"Patient/$patient"},"effectivePeriod":{"start":"$since"},
      "dosage":[{"text":"$dose"}]}"""

  private fun allergy(
    id: String,
    patient: String,
    code: String,
    display: String,
    reaction: Pair<String, String>?,
  ): String {
    val reacted =
      reaction
        ?.let { (manifestation, text) ->
          ""","reaction":[{"manifestation":[{"coding":[{"system":"$SNOMED","code":"$manifestation","display":"$text"}],"text":"$text"}]}]"""
        }
        .orEmpty()
    return """{"resourceType":"AllergyIntolerance","id":"$id",
      "clinicalStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical","code":"active"}]},
      "verificationStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/allergyintolerance-verification","code":"confirmed"}]},
      "code":{"coding":[{"system":"$SNOMED","code":"$code","display":"$display"}],"text":"$display"},
      "patient":{"reference":"Patient/$patient"}$reacted}"""
  }

  private fun pressure(code: String, display: String, value: Int) =
    """{"code":{"coding":[{"system":"$LOINC","code":"$code","display":"$display"}]},
      "valueQuantity":{"value":$value,"unit":"mmHg","system":"http://unitsofmeasure.org","code":"mm[Hg]"}}"""

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
