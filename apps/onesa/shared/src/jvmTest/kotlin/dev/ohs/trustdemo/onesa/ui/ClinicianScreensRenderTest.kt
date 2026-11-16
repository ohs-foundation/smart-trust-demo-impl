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
package dev.ohs.trustdemo.onesa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ohs.fhir.model.r4.ConceptMap
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.trustdemo.hcert.FailureReason
import dev.ohs.trustdemo.hcert.InMemoryTrustListStorage
import dev.ohs.trustdemo.hcert.ReadResult
import dev.ohs.trustdemo.hcert.TrustListCache
import dev.ohs.trustdemo.hcert.TrustListSnapshot
import dev.ohs.trustdemo.hcert.VerificationResult
import dev.ohs.trustdemo.hcert.VerifyStep
import dev.ohs.trustdemo.onesa.buildAppViewRegistry
import dev.ohs.trustdemo.onesa.data.hcert.BundledTrustLists
import dev.ohs.trustdemo.onesa.data.hcert.DvcHolder
import dev.ohs.trustdemo.onesa.data.hcert.HcertService
import dev.ohs.trustdemo.onesa.data.hcert.PinnedTrustList
import dev.ohs.trustdemo.onesa.data.hcert.SignedCards
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.repository.PatientContexts
import dev.ohs.trustdemo.onesa.data.repository.PatientRepository
import dev.ohs.trustdemo.onesa.data.schedule.BookedVisits
import dev.ohs.trustdemo.onesa.data.schedule.BookingOutcome
import dev.ohs.trustdemo.onesa.data.schedule.DisplayOnlyRows
import dev.ohs.trustdemo.onesa.data.schedule.DoseRecording
import dev.ohs.trustdemo.onesa.data.schedule.SchedulePlans
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleService
import dev.ohs.trustdemo.onesa.data.schedule.Standing
import dev.ohs.trustdemo.onesa.data.verification.DoseMatchService
import dev.ohs.trustdemo.onesa.data.verification.ForeignDoses
import dev.ohs.trustdemo.onesa.data.verification.PendingImport
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.data.verification.ScanOutcome
import dev.ohs.trustdemo.onesa.data.verification.ScanRequest
import dev.ohs.trustdemo.onesa.data.verification.ScanTransactions
import dev.ohs.trustdemo.onesa.data.verification.VerifiedCard
import dev.ohs.trustdemo.onesa.feature.home.HomeShell
import dev.ohs.trustdemo.onesa.feature.home.ShellHeader
import dev.ohs.trustdemo.onesa.feature.patient.list.PatientListContent
import dev.ohs.trustdemo.onesa.feature.patient.profile.PatientProfileContent
import dev.ohs.trustdemo.onesa.feature.patient.profile.ProfileTab
import dev.ohs.trustdemo.onesa.feature.patient.profile.RecordDoseContent
import dev.ohs.trustdemo.onesa.feature.patient.profile.RecordDoseForm
import dev.ohs.trustdemo.onesa.feature.referral.ClinicScheduleContent
import dev.ohs.trustdemo.onesa.feature.referral.DoseMatchContent
import dev.ohs.trustdemo.onesa.feature.referral.InboxToday
import dev.ohs.trustdemo.onesa.feature.referral.ReferralInboxContent
import dev.ohs.trustdemo.onesa.feature.referral.ScheduleContent
import dev.ohs.trustdemo.onesa.feature.referral.ScheduleUiState
import dev.ohs.trustdemo.onesa.feature.referral.VerifyActions
import dev.ohs.trustdemo.onesa.feature.referral.VerifyCardsContent
import dev.ohs.trustdemo.onesa.feature.referral.VerifyCardsUiState
import dev.ohs.trustdemo.onesa.feature.trustlist.TrustKeys
import dev.ohs.trustdemo.onesa.feature.trustlist.TrustListContent
import dev.ohs.trustdemo.onesa.feature.trustlist.toState
import dev.ohs.trustdemo.onesa.ui.ScreenshotHarness.render
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.onesa.util.timeAgo
import dev.ohs.trustdemo.trustkit.StandIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The clinician's screens, drawn from the IG's records as each step of J3 leaves them. */
@OptIn(ExperimentalTestApi::class)
class ClinicianScreensRenderTest {
  private fun ig(name: String): Resource =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      File("../../../ig/resources/$name.json").readText(),
    )

  private val somsak = "TrustDemoSomsak"
  private val visitDay = LocalDate.parse("2025-11-25")
  private val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
  private val listFetched = now.epochSeconds - 2 * 60 * 60

  /** The records at J3a: the referral, the reported doses and who is who. */
  private val referred =
    listOf(
      "Patient-TrustDemoSomsak",
      "Group-TrustDemoHousehold",
      "ServiceRequest-TrustDemoJ2Referral",
      "Immunization-TrustDemoJ2HepBReported",
      "Immunization-TrustDemoJ2BcgReported",
      "Practitioner-TrustDemoVilay",
      "Practitioner-TrustDemoChanthason",
      "CodeSystem-TrustDemoOnesaEpiSchedule",
    )

  private fun repositoryOf(names: List<String>, vararg more: Resource) =
    InMemorySampleFhirRepository().apply {
      runBlocking {
        names.forEach { upsert(ig(it)) }
        more.forEach { upsert(it) }
      }
    }

  private val seededMap =
    (ig("ConceptMap-TrustDemoProductToOnesaEpi") as ConceptMap).let { map ->
      map.copy(
        group =
          map.group.map { group ->
            group.copy(element = group.element.filter { it.code?.value != HEPB })
          }
      )
    }

  /** The Hep B dose as the import leaves it: verified, with no schedule code until she matches. */
  private val importedHepB =
    (ig("Immunization-TrustDemoJ3HepB") as Immunization).let { dose ->
      dose.copy(
        vaccineCode =
          dose.vaccineCode.copy(
            coding = dose.vaccineCode.coding.filterNot { it.code?.value == "XXK-EPI-HEPB-BD" }
          )
      )
    }

  private val completedReferral =
    (ig("ServiceRequest-TrustDemoJ2Referral") as ServiceRequest).copy(
      status = Enumeration(value = ServiceRequest.RequestStatus.Completed)
    )

  private val hcert =
    HcertService(
      local =
        PinnedTrustList(
          name = "Local test list",
          signer = BundledTrustLists::localSigner,
          cache =
            TrustListCache(
              InMemoryTrustListStorage().apply {
                runBlocking {
                  write(
                    TrustListSnapshot(
                      File("../../../testkit/local-test-list.json").readText(),
                      "https://trust.gdhcn.example/local-test-list.json",
                      listFetched,
                    )
                  )
                }
              },
              url = "https://trust.gdhcn.example/local-test-list.json",
            ),
        ),
      gdhcnDev = BundledTrustLists.gdhcnDev(InMemoryTrustListStorage()),
    )

  private val holder = DvcHolder("Somsak SENGMANY", "male", "2025-10-28", "XXR-4821")

  private val hepBCard =
    SignedCards.dvc(StandIn.XXR_CARD, now.epochSeconds, HEPB, "2025-10-28", holder)

  private val clinicHeader =
    ShellHeader(
      title = "Lomaru District Health Center — OHS Player Facility EMR",
      icon = OnesaIcons.Building,
      subtitle = "Practitioner: Nurse Chanthason",
      chips =
        listOf(
          Status(
            "Trust List Cached • Updated ${timeAgo(listFetched, now.epochSeconds)}",
            Tone.Success,
          )
        ),
    )

  private val clinicTabs = listOf("Referrals (1)", "Patients", "Schedule", "Trust list", "Sync")

  private val noActions = VerifyActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {})

  @Composable
  private fun ClinicTopBar() =
    OnesaTopBar(
      clinicHeader.title,
      clinicHeader.icon,
      subtitle = clinicHeader.subtitle,
      chips = clinicHeader.chips,
    )

  @Composable
  private fun Registry(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalViewRegistry provides buildAppViewRegistry(), content = content)

  private fun clinic(
    name: String,
    size: Pair<Dp, Dp> = ScreenshotHarness.Tablet,
    content: @Composable () -> Unit,
  ) = render(name, size, OnesaColors.BrandClinic) { Registry(content) }

  @Test
  fun referralInbox() {
    val inbox = ReferralInbox(repositoryOf(referred))
    val referral = runBlocking { inbox.get("TrustDemoJ2Referral")!! }
    clinic("1-11-referral-inbox") {
      HomeShell(
        header = clinicHeader,
        tabs = clinicTabs,
        selected = 0,
        onSelect = {},
        menu = {},
        actions = listOf(Action("Open Referral", {})),
        side = { InboxToday(visitDay, "Synced 08:14") },
      ) {
        ReferralInboxContent(
          referrals = listOf(referral),
          place = "Lomaru District Health Center",
          selected = referral.id,
          onSelect = {},
        )
      }
    }
  }

  private fun verifyState(): VerifyCardsUiState {
    val repository = repositoryOf(referred)
    val inbox = ReferralInbox(repository)
    val referral = runBlocking { inbox.get("TrustDemoJ2Referral")!! }
    return VerifyCardsUiState(
      referral = referral,
      child = runBlocking { PatientContexts(repository).of(somsak) },
      reported = runBlocking { inbox.reportedDoses(somsak) },
    )
  }

  @Test
  fun verifyCardsAndTheFailedCheck() {
    val state = verifyState()
    val verified =
      (runBlocking { hcert.verifyCard(hepBCard, now.epochSeconds) } as VerificationResult.Verified)
        .hcert
    fun pendingOf(childName: String, nameDiffers: Boolean) =
      PendingImport(
        state.referral!!,
        hepBCard,
        VerifiedCard(verified.dvc!!, verified, "https://trust.gdhcn.example/local-test-list.json"),
        "Somsak SENGMANY",
        "2025-10-28",
        childName,
        "Hepatitis B vaccine, paediatric, monovalent, 2025-10-28",
        runBlocking { hcert.freshness(verified.dsc, now.epochSeconds) },
        nameDiffers,
      )
    val pending = pendingOf("Somsak Sengmany", nameDiffers = false)
    clinic("0-03-s4-verify", 1024.dp to 900.dp) {
      VerifyCardsContent(
        state = state.copy(pending = pending, scanned = hepBCard),
        today = visitDay,
        topBar = { ClinicTopBar() },
        showOutcome = true,
        actions = noActions,
      )
    }

    val differs =
      state.copy(pending = pendingOf("Noy Sengmany", nameDiffers = true), scanned = hepBCard)
    assertFalse(differs.canConfirm)
    assertFalse(differs.copy(overrideReason = "  ").canConfirm)
    assertTrue(differs.copy(overrideReason = "Card uses a nickname").canConfirm)
    assertTrue(state.copy(pending = pending).canConfirm)
    clinic("1-14-s4-name-differs", 1024.dp to 1100.dp) {
      VerifyCardsContent(
        state = differs.copy(overrideReason = "Card uses a nickname"),
        today = visitDay,
        topBar = { ClinicTopBar() },
        showOutcome = true,
        actions = noActions,
      )
    }

    val forged = tampered(hepBCard)
    val failure =
      (runBlocking { hcert.verifyCard(forged, now.epochSeconds) } as VerificationResult.Failed)
        .failure
    check(failure.step == VerifyStep.SIGNATURE && failure.reason == FailureReason.SIGNATURE_INVALID)
    val kid = (runBlocking { hcert.read(forged) } as ReadResult.Decoded).hcert.kid
    clinic("1-12-verification-failed", 1024.dp to 760.dp) {
      VerifyCardsContent(
        state =
          state.copy(
            scanned = forged,
            results = listOf(ScanOutcome.Failed(failure.step, failure.reason, kid)),
          ),
        today = visitDay,
        topBar = { ClinicTopBar() },
        showOutcome = true,
        actions = noActions,
      )
    }
  }

  @Test
  fun doseMatchPicker() {
    val repository =
      repositoryOf(
        referred.filterNot { it.contains("Reported") },
        seededMap,
        importedHepB,
        ig("Immunization-TrustDemoJ3Bcg"),
        ig("Provenance-TrustDemoJ3Verification"),
      )
    val matching =
      DoseMatchService(repository, { null }, HttpClient(MockEngine { error("") }), "", { true })
    val unmatched = runBlocking { matching.unmatched(somsak) }
    val choices = runBlocking { matching.choices() }
    val state =
      VerifyCardsUiState(
        referral = runBlocking { ReferralInbox(repository).get("TrustDemoJ2Referral") },
        child = runBlocking { PatientContexts(repository).of(somsak) },
        doses = runBlocking { ForeignDoses(repository).of(somsak) },
        unmatched = unmatched,
        choices = choices,
      )
    clinic("1-13-dose-match", 1024.dp to 700.dp) {
      DoseMatchContent(
        state = state,
        today = visitDay,
        choice = choices.first { it.code == "XXK-EPI-HEPB-BD" },
        onChoose = {},
        actions = noActions,
      )
    }
  }

  @Test
  fun scheduleAndBooking() {
    val audit =
      ScanTransactions.alreadyImported(
          ScanRequest(hepBCard, somsak, "TrustDemoChanthason", "TrustDemoLomaruDhc", now)
        )
        .getValue("entry")
        .jsonArray
        .single()
        .jsonObject
        .getValue("resource")
    val repository =
      repositoryOf(
        referred.filterNot { it.contains("Reported") } +
          listOf(
            "ConceptMap-TrustDemoProductToOnesaEpi",
            "Immunization-TrustDemoJ3HepB",
            "Immunization-TrustDemoJ3Bcg",
            "Provenance-TrustDemoJ3Verification",
            "Provenance-TrustDemoJ3Adjudication",
          ),
        FhirJson.instance.decodeFromJsonElement(Resource.serializer(), audit),
      )
    val schedule = runBlocking {
      ScheduleService(repository, { plans() }) { visitDay }.evaluate(somsak)
    }
    val inbox = ReferralInbox(repository)
    val state =
      ScheduleUiState(
        referral = runBlocking { inbox.get("TrustDemoJ2Referral") },
        child = runBlocking { PatientContexts(repository).of(somsak) },
        doses = runBlocking { ForeignDoses(repository).of(somsak) },
        schedule = schedule,
        busy = false,
        booked =
          BookingOutcome.Booked(
            schedule.visit,
            schedule.items.count { it.standing is Standing.Due },
          ),
      )
    val upcoming = DisplayOnlyRows.parse(File("$SCHEDULE/s5-display-only-rows.json").readText())
    clinic("0-04-s5-schedule", 1024.dp to 900.dp) {
      ScheduleContent(state, visitDay, upcoming, onBack = {}, onBook = {})
    }
    runComposeUiTest {
      setContent {
        OnesaTheme(OnesaColors.BrandClinic) {
          Registry { ScheduleContent(state, visitDay, upcoming, onBack = {}, onBook = {}) }
        }
      }
      listOf(
          "10 Weeks" to "Penta-2, OPV-2, PCV-2",
          "14 Weeks" to "Penta-3, OPV-3, PCV-3, IPV-1",
          "9 Months" to "Measles-rubella 1, JE",
        )
        .forEach { (age, doses) ->
          onNodeWithText(age).assertExists()
          onNodeWithText(doses).assertExists()
        }
      assertEquals(
        3,
        onAllNodesWithText("Upcoming").fetchSemanticsNodes().size,
        "10, 14 weeks and 9 months",
      )
    }
  }

  private fun afterVisit(): InMemorySampleFhirRepository =
    repositoryOf(
      referred.filterNot { it.contains("Reported") || it.contains("ServiceRequest") } +
        listOf(
          "ConceptMap-TrustDemoProductToOnesaEpi",
          "Immunization-TrustDemoJ3HepB",
          "Immunization-TrustDemoJ3Bcg",
          "Provenance-TrustDemoJ3Verification",
          "Provenance-TrustDemoJ3Adjudication",
          "ImmunizationRecommendation-TrustDemoJ3Recommendation",
          "Task-TrustDemoJ3Reminder",
        ),
      completedReferral,
    )

  @Test
  fun patientProfile() {
    val profile = runBlocking { PatientRepository(afterVisit()).getPatientProfile(somsak) }
    clinic("1-14-patient-profile", 1024.dp to 700.dp) {
      PatientProfileContent(
        state = profile,
        today = visitDay,
        tab = ProfileTab.Doses,
        onTab = {},
        topBar = { ClinicTopBar() },
        actions = listOf(Action("Record a Dose", {})),
      )
    }
  }

  @Test
  fun recordADose() {
    val repository = afterVisit()
    val profile = runBlocking { PatientRepository(repository).getPatientProfile(somsak) }
    val matching =
      DoseMatchService(repository, { null }, HttpClient(MockEngine { error("") }), "", { true })
    val recording =
      DoseRecording(
        repository,
        matching::choices,
        { RegistrationSession("token", "TrustDemoChanthason", "TrustDemoLomaruDhc") },
        HttpClient(MockEngine { error("") }),
        "",
        { true },
        { true },
      )
    val doses = runBlocking { recording.doses() }
    val penta = doses.first { it.code == "XXK-EPI-PENTA-1" }
    val products = runBlocking { recording.products(penta) }
    val day = LocalDate.parse("2025-12-10")
    clinic("1-16-record-dose", 1024.dp to 600.dp) {
      RecordDoseContent(
        form =
          RecordDoseForm(
            doses = doses,
            dose = penta,
            products = products,
            product = products.single(),
            lotNumber = "XXK-2201",
            date = day.toString(),
            recordingId = "TrustDemoOnesaPenta1",
          ),
        state = profile,
        givenBy = "Nurse Chanthason",
        today = day,
        onBack = {},
        onPickDose = {},
        onPickProduct = {},
        onLot = {},
        onDate = {},
        onSave = {},
      )
    }
  }

  @Test
  fun trustListStatus() {
    val lists = runBlocking { hcert.statuses(now.epochSeconds) }.map { it.toState() }
    clinic("1-15-trust-list") {
      HomeShell(
        header = clinicHeader,
        tabs = clinicTabs,
        selected = 3,
        onSelect = {},
        menu = {},
        actions = listOf(Action("Refresh Now", {}, primary = false)),
        side = { TrustKeys(lists) },
      ) {
        TrustListContent(lists)
      }
    }
  }

  @Test
  fun patientsAndScheduleTabs() {
    val repository = afterVisit()
    val patients = runBlocking { PatientRepository(repository).getPatients() }
    clinic("clinician-patients") {
      HomeShell(header = clinicHeader, tabs = clinicTabs, selected = 1, onSelect = {}, menu = {}) {
        PatientListContent(patients, "Lomaru District Health Center", onPatientClick = {})
      }
    }
    val visits = runBlocking { BookedVisits(repository).all() }
    clinic("clinician-schedule") {
      HomeShell(header = clinicHeader, tabs = clinicTabs, selected = 2, onSelect = {}, menu = {}) {
        ClinicScheduleContent(visits, visitDay, onOpenPatient = {})
      }
    }
  }

  /** [hc1] with the last byte of its COSE signature flipped, as a tampered card carries it. */
  private fun tampered(hc1: String): String {
    val cose =
      Inflater().run {
        setInput(base45Decode(hc1.removePrefix("HC1:")))
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!finished()) out.write(buffer, 0, inflate(buffer))
        out.toByteArray()
      }
    cose[cose.lastIndex] = (cose.last().toInt() xor 0x01).toByte()
    val deflated =
      Deflater().run {
        setInput(cose)
        finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!finished()) out.write(buffer, 0, deflate(buffer))
        out.toByteArray()
      }
    return "HC1:" + base45Encode(deflated)
  }

  private fun base45Decode(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    text.chunked(3).forEach { chunk ->
      val value = chunk.foldIndexed(0) { i, sum, c -> sum + BASE45.indexOf(c) * pow45(i) }
      if (chunk.length == 3) out.write(value shr 8)
      out.write(value and 0xff)
    }
    return out.toByteArray()
  }

  private fun base45Encode(bytes: ByteArray): String = buildString {
    bytes.toList().chunked(2).forEach { pair ->
      var value = pair.fold(0) { sum, b -> sum * 256 + (b.toInt() and 0xff) }
      repeat(if (pair.size == 2) 3 else 2) {
        append(BASE45[value % 45])
        value /= 45
      }
    }
  }

  private fun pow45(exponent: Int): Int = (1..exponent).fold(1) { product, _ -> product * 45 }

  private fun plans(): SchedulePlans {
    val protocols = "src/commonMain/composeResources/files/protocols"
    return SchedulePlans.parse(
      File("$protocols/PlanDefinition-TrustDemoDT03.json").readText(),
      File("$protocols/PlanDefinition-TrustDemoOnesaInfantSchedule.json").readText(),
    )
  }

  private companion object {
    const val HEPB = "vp-hepb-peds-monovalent"
    const val SCHEDULE = "src/commonMain/composeResources/files/schedule"
    const val BASE45 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
  }
}
