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
package dev.ohs.trustdemo.onesa.data.sharing

import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.trustdemo.hcert.CertificateFingerprint
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.util.FhirJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** The wallet reads roles and doses from what the family phone syncs, with no RelatedPerson. */
class FamilyRecordsTest {
  private fun ig(name: String): Resource =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      File("../../../ig/resources/$name.json").readText(),
    )

  private val people =
    listOf(
      Person("TrustDemoSomsak", "Somsak Sengmany"),
      Person("TrustDemoBounmyPatient", "Bounmy Sengmany"),
      Person("TrustDemoKhamlaPatient", "Khamla Sengmany"),
    )

  @Test
  fun rolesComeFromTheChildsContactsAndTheGroupsManagingEntity() = runTest {
    val repository =
      InMemorySampleFhirRepository().apply {
        listOf(
            "Group-TrustDemoHousehold",
            "Patient-TrustDemoSomsak",
            "Patient-TrustDemoBounmyPatient",
            "Patient-TrustDemoKhamlaPatient",
          )
          .forEach { upsert(ig(it)) }
      }

    val family = FamilyRecords(repository).members(people).associateBy { it.id }

    assertEquals(
      listOf(
        Triple("Somsak", FamilyRecords.CHILD, false),
        Triple("Bounmy", "FTH", true),
        Triple("Khamla", "MTH", false),
      ),
      people.map { family.getValue(it.id).let { m -> Triple(m.given, m.relation, m.head) } },
    )
    assertEquals("2025-10-28", family.getValue("TrustDemoSomsak").birthDate)
  }

  @Test
  fun aDoseFromACardIsTheOneThatCarriesTheCardsFingerprint() = runTest {
    val fromCard =
      (ig("Immunization-TrustDemoJ3HepB") as Immunization).let {
        it.copy(
          identifier =
            listOf(
              Identifier(
                system = Uri(value = CertificateFingerprint.SYSTEM),
                value = FhirString(value = CertificateFingerprint.of("HC1:card")),
              )
            )
        )
      }
    val repository =
      InMemorySampleFhirRepository().apply {
        upsert(fromCard)
        upsert(ig("Immunization-TrustDemoOnesaPenta1"))
      }

    val doses = FamilyRecords(repository).doses("TrustDemoSomsak")

    assertEquals(
      listOf(
        listOf("Hepatitis B birth dose", "XXU-EPI-HEPB-BD", "XXR-4821", true, true),
        listOf("Pentavalent (DTP-HepB-Hib), dose 1", "XXU-EPI-PENTA-1", "XXU-2201", true, false),
      ),
      doses.map { listOf(it.name, it.scheduleCode, it.lot, it.primarySource, it.fromCard) },
    )
  }

  @Test
  fun aMembersOwnHealthRecordsAreReadForTheirSummary() = runTest {
    fun record(json: String) = FhirJson.instance.decodeFromString(Resource.serializer(), json)
    val bounmy = "Patient/TrustDemoBounmyPatient"
    val repository =
      InMemorySampleFhirRepository().apply {
        upsert(
          record(
            """{"resourceType":"Condition","id":"htn","subject":{"reference":"$bounmy"},
              "clinicalStatus":{"coding":[{"code":"active"}]},
              "code":{"coding":[{"display":"Essential hypertension"}],"text":"Essential hypertension"}}"""
          )
        )
        upsert(
          record(
            """{"resourceType":"Condition","id":"anaemia",
              "subject":{"reference":"Patient/TrustDemoKhamlaPatient"},
              "code":{"text":"Iron deficiency anaemia"}}"""
          )
        )
        upsert(
          record(
            """{"resourceType":"MedicationStatement","id":"amlodipine","status":"active",
              "subject":{"reference":"$bounmy"},"medicationCodeableConcept":{"text":"amlodipine"},
              "dosage":[{"text":"5 mg by mouth once daily"}]}"""
          )
        )
        upsert(
          record(
            """{"resourceType":"AllergyIntolerance","id":"penicillin","patient":{"reference":"$bounmy"},
              "code":{"text":"Allergy to penicillin"},
              "reaction":[{"manifestation":[{"text":"Eruption of skin"}]}]}"""
          )
        )
        upsert(
          record(
            """{"resourceType":"Observation","id":"bp","status":"final","subject":{"reference":"$bounmy"},
              "code":{"text":"Blood pressure"},"effectiveDateTime":"2026-10-25",
              "component":[
                {"code":{"text":"Systolic"},"valueQuantity":{"value":148,"unit":"mmHg"}},
                {"code":{"text":"Diastolic"},"valueQuantity":{"value":94,"unit":"mmHg"}}]}"""
          )
        )
      }

    val health = FamilyRecords(repository).health("TrustDemoBounmyPatient")

    assertEquals(listOf(HealthItem("Essential hypertension", "active")), health.problems)
    assertEquals(listOf(HealthItem("amlodipine", "5 mg by mouth once daily")), health.medications)
    assertEquals(listOf(HealthItem("Allergy to penicillin", "Eruption of skin")), health.allergies)
    assertEquals(
      listOf(HealthItem("Blood pressure", "148/94 mmHg • 2026-10-25")),
      health.vitalSigns,
    )
  }
}
