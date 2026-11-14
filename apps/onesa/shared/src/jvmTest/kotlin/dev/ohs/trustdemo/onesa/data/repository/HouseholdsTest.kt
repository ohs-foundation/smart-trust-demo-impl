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
package dev.ohs.trustdemo.onesa.data.repository

import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.RelatedPerson
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.util.FhirJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** The household and referral screens read the IG's own records. */
class HouseholdsTest {
  private fun ig(name: String): Resource =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      File("../../../ig/resources/$name.json").readText(),
    )

  private val group = ig("Group-TrustDemoHousehold") as Group
  private val bounmy = ig("Patient-TrustDemoBounmyPatient") as Patient
  private val khamla = ig("Patient-TrustDemoKhamlaPatient") as Patient
  private val somsak = ig("Patient-TrustDemoSomsak") as Patient
  private val related =
    listOf(ig("RelatedPerson-TrustDemoBounmy"), ig("RelatedPerson-TrustDemoKhamla")).map {
      it as RelatedPerson
    }

  @Test
  fun rolesComeFromTheManagingEntityAndTheParentsRecords() {
    val roles = householdRoles(group, listOf(bounmy, khamla, somsak), related)

    assertEquals("TrustDemoBounmyPatient", roles.headMemberId)
    assertEquals(
      mapOf(
        "TrustDemoBounmyPatient" to "FTH",
        "TrustDemoKhamlaPatient" to "MTH",
        "TrustDemoSomsak" to "CHILD",
      ),
      roles.relations,
    )
    assertEquals(
      HouseholdRoles(),
      householdRoles(group.copy(managingEntity = null), listOf(bounmy, khamla), emptyList()),
    )
  }

  @Test
  fun theRegisterAndTheProfileShowTheHeadAndTheHouseholdId() = runTest {
    val repository = InMemorySampleFhirRepository()
    (listOf(group, bounmy, khamla, somsak) + related).forEach { repository.upsert(it) }
    val groups = GroupRepository(repository)

    val row = groups.getGroups().single()
    assertEquals("HH-XXK-204", row.householdId)
    assertEquals(listOf("TrustDemoOnesaHousehold"), groups.householdProfiles())
    assertEquals("Bounmy Sengmany", personName(row.headGivenName, row.headFamilyName))

    val profile = groups.getGroupProfile("TrustDemoHousehold")
    assertEquals("TrustDemoBounmyPatient", profile.headMemberId)
    assertEquals(listOf("FTH", "MTH", "CHILD"), profile.members.map { it.relationshipCode })
  }

  @Test
  fun agesAreWeeksThenMonthsThenYears() {
    val today = LocalDate(2026, 10, 28)
    assertEquals("4w", shortAge("2026-09-30", today))
    assertEquals("12m", shortAge("2025-10-28", today))
    assertEquals("29y", shortAge("1997-03-01", today))
    assertEquals(null, shortAge(null, today))
  }

  @Test
  fun theChwSeesOnlyTheReferralsSheSent() = runTest {
    val repository = InMemorySampleFhirRepository()
    val referral = ig("ServiceRequest-TrustDemoJ2Referral")
    listOf(referral, somsak, ig("Organization-TrustDemoLomaruDhc")).forEach {
      repository.upsert(it)
    }
    val inbox = ReferralInbox(repository)

    val sent = inbox.sent("TrustDemoVilay").single()
    assertEquals("Somsak Sengmany", sent.patientName)
    assertEquals("Lomaru District Health Center (demonstrator)", sent.performer)
    assertEquals("active", sent.status)
    assertEquals(emptyList(), inbox.sent("TrustDemoChanthason"))
  }
}
