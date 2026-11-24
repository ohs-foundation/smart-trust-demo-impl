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
package dev.ohs.trustdemo.onesa.data.verification

import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.hcert.trustEntries
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.repository.PatientContexts
import dev.ohs.trustdemo.onesa.data.schedule.BookedVisits
import dev.ohs.trustdemo.onesa.util.FhirJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The clinician's screens read the IG's own records for what they show beside the doses. */
class ClinicRecordsTest {
  private fun ig(name: String): Resource =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      File("../../../ig/resources/$name.json").readText(),
    )

  private suspend fun repositoryOf(vararg names: String) =
    InMemorySampleFhirRepository().apply { names.forEach { upsert(ig(it)) } }

  @Test
  fun theInboxNamesWhoReferredWhatForAndHowSoon() = runTest {
    val inbox =
      ReferralInbox(
        repositoryOf(
          "ServiceRequest-TrustDemoJ2Referral",
          "Patient-TrustDemoSomsak",
          "Practitioner-TrustDemoVilay",
          "Immunization-TrustDemoJ2HepBReported",
        )
      )

    val referral = inbox.get("TrustDemoJ2Referral")!!

    assertEquals("Vilay", referral.requester)
    assertEquals(
      "Verify foreign paper DVCs and map the birth doses to the national schedule",
      referral.purpose,
    )
    assertEquals("routine", referral.priority)
    val reported = inbox.reportedDoses("TrustDemoSomsak").single()
    assertEquals(
      "XXR-4821" to "Kesaro Provincial Hospital, Taifafeki",
      reported.lot to reported.origin,
    )
    assertEquals(listOf("active"), inbox.about("TrustDemoSomsak").map { it.status })
  }

  @Test
  fun urgentReferralsComeFirst() = runTest {
    val referral =
      FhirJson.instance
        .parseToJsonElement(
          File("../../../ig/resources/ServiceRequest-TrustDemoJ2Referral.json").readText()
        )
        .jsonObject
    fun copy(id: String, authoredOn: String, priority: String) =
      FhirJson.instance.decodeFromJsonElement(
        Resource.serializer(),
        JsonObject(
          referral +
            mapOf(
              "id" to JsonPrimitive(id),
              "authoredOn" to JsonPrimitive(authoredOn),
              "priority" to JsonPrimitive(priority),
            )
        ),
      )
    val repository =
      repositoryOf("Patient-TrustDemoSomsak").apply {
        upsert(copy("routine-new", "2025-11-20", "routine"))
        upsert(copy("urgent-old", "2025-11-01", "urgent"))
        upsert(copy("routine-old", "2025-11-02", "routine"))
      }

    val waiting = ReferralInbox(repository).observe().first()

    assertEquals(listOf("urgent-old", "routine-new", "routine-old"), waiting.map { it.id })
    assertEquals(listOf("urgent", "routine", "routine"), waiting.map { it.priority })
  }

  @Test
  fun foreignDosesSayHowTheyReachTheScheduleAndWhoConfirmedIt() = runTest {
    val repository =
      repositoryOf(
        "Immunization-TrustDemoJ3HepB",
        "Immunization-TrustDemoJ3Bcg",
        "Immunization-TrustDemoOnesaPenta1",
        "Provenance-TrustDemoJ3Verification",
        "Provenance-TrustDemoJ3Adjudication",
        "ConceptMap-TrustDemoProductToOnesaEpi",
        "CodeSystem-TrustDemoOnesaEpiSchedule",
        "Practitioner-TrustDemoChanthason",
      )

    val doses = ForeignDoses(repository).of("TrustDemoSomsak").associateBy { it.product }

    assertEquals(
      setOf("a3K3X000006MiiXUAS", "a3K3X000006MiisUAC", "a3K3X000006MijGUAS"),
      doses.keys,
    )
    val hepB = doses.getValue("a3K3X000006MiiXUAS")
    assertEquals(EpiDose("XXU-EPI-HEPB-BD", "Hepatitis B birth dose"), hepB.schedule)
    assertTrue(hepB.mapped && hepB.verified)
    assertEquals(Adjudication("Nurse Chanthason", "2025-11-25T10:21:00+07:00"), hepB.adjudication)
    assertEquals("Kesaro Provincial Hospital, Taifafeki" to "XXR-4821", hepB.origin to hepB.lot)
    val bcg = doses.getValue("a3K3X000006MiisUAC")
    assertTrue(bcg.verified)
    assertNull(bcg.adjudication)
    val penta = doses.getValue("a3K3X000006MijGUAS")
    assertTrue(!penta.verified, "the clinic's own dose has no card to verify")

    val hepBRecord = repository.get("Immunization", "TrustDemoJ3HepB") as Immunization
    repository.upsert(
      hepBRecord.copy(
        vaccineCode =
          hepBRecord.vaccineCode.copy(
            coding = hepBRecord.vaccineCode.coding.filterNot { it.code?.value == "XXU-EPI-HEPB-BD" }
          )
      )
    )
    repository.remove("ConceptMap", "TrustDemoProductToOnesaEpi")
    val gap = ForeignDoses(repository).of("TrustDemoSomsak").first { it.id == "TrustDemoJ3HepB" }
    assertNull(gap.schedule)
    assertTrue(!gap.mapped)
  }

  @Test
  fun aChildsContextComesFromTheHouseholdReferralAuditsAndProvenance() = runTest {
    val repository =
      repositoryOf(
        "Patient-TrustDemoSomsak",
        "Group-TrustDemoHousehold",
        "ServiceRequest-TrustDemoJ2Referral",
        "Immunization-TrustDemoJ3HepB",
        "Immunization-TrustDemoJ3Bcg",
        "Provenance-TrustDemoJ3Verification",
        "Provenance-TrustDemoJ3Adjudication",
        "AuditEvent-TrustDemoJ41Access",
      )
    val scan =
      ScanRequest(
        "HC1:card",
        "TrustDemoSomsak",
        "TrustDemoChanthason",
        "TrustDemoLomaruDhc",
        Instant.parse("2025-11-25T03:14:22Z"),
        newId = { "scan-audit" },
      )
    val audit =
      ScanTransactions.alreadyImported(scan)
        .getValue("entry")
        .jsonArray
        .single()
        .jsonObject
        .getValue("resource")
    repository.upsert(FhirJson.instance.decodeFromJsonElement(Resource.serializer(), audit))

    val child = PatientContexts(repository).of("TrustDemoSomsak")!!

    assertEquals("Somsak Sengmany", child.name)
    assertEquals("XXU-TMP-2025-00918", child.temporaryId)
    assertEquals(
      Triple("TrustDemoHousehold", "Sengmany household", "HH-XXU-204"),
      Triple(child.groupId, child.household, child.householdId),
    )
    assertEquals("active", child.referralStatus)
    assertEquals(1, child.scanAudits, "the VHL access audit is not a card scan")
    assertEquals(mapOf("TrustDemoJ3Verification" to 2), child.removals)
  }

  @Test
  fun theClinicScheduleListsEachRequestedReminder() = runTest {
    val repository =
      repositoryOf(
        "Patient-TrustDemoSomsak",
        "ImmunizationRecommendation-TrustDemoJ3Recommendation",
        "Task-TrustDemoJ3Reminder",
      )

    val visit = BookedVisits(repository).all().single()

    assertEquals("Somsak Sengmany", visit.patientName)
    assertEquals("TrustDemoJ3Reminder", visit.visit.reminderId)
    assertEquals("2025-12-09" to "2025-12-16", visit.visit.reminderFrom to visit.visit.reminderTo)
    assertEquals(
      listOf("XXU-EPI-PENTA-1", "XXU-EPI-OPV-1", "XXU-EPI-PCV-1"),
      visit.visit.doses.map { it.code },
    )
  }

  @Test
  fun trustListKeysCarryTheirDomainParticipantUsageAndKid() {
    val trust = "src/commonMain/composeResources/files/trust"
    val gdhcn = trustEntries(File("$trust/gdhcn-dev-trustlist.json").readText())
    val local = trustEntries(File("../../../testkit/local-test-list.json").readText())

    assertEquals(234, gdhcn.size)
    assertEquals(98, gdhcn.count { it.signsDocuments })
    assertEquals(
      listOf("916ffa567c0f44a2"),
      gdhcn.filter { it.participant == "XXR" && it.signsDocuments }.map { it.kid },
    )
    assertTrue(gdhcn.none { it.testOnly })
    assertEquals(
      listOf(
        Triple("DEMO-DVC", "XXR", "DSC"),
        Triple("DEMO-VHL", "XXU", "DSC"),
        Triple("DEMO-VHL", "XXR", "CSCA"),
      ),
      local.map { Triple(it.domain, it.participant, it.usage) },
    )
    assertTrue(local.all { it.testOnly })
  }
}
