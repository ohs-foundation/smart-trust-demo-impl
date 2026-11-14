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

import dev.ohs.fhir.engine.sync.SyncJobStatus
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.control.Dates
import dev.ohs.trustdemo.control.Step
import dev.ohs.trustdemo.control.expectations
import dev.ohs.trustdemo.hcert.CertificateFingerprint
import dev.ohs.trustdemo.hcert.FailureReason
import dev.ohs.trustdemo.hcert.InMemoryTrustListStorage
import dev.ohs.trustdemo.hcert.TrustListCache
import dev.ohs.trustdemo.hcert.VerifyStep
import dev.ohs.trustdemo.onesa.data.hcert.BundledTrustLists
import dev.ohs.trustdemo.onesa.data.hcert.HcertService
import dev.ohs.trustdemo.onesa.data.hcert.PinnedTrustList
import dev.ohs.trustdemo.onesa.data.hcert.SignedCards
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.schedule.BookingOutcome
import dev.ohs.trustdemo.onesa.data.schedule.SchedulePlans
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleService
import dev.ohs.trustdemo.onesa.data.schedule.VisitBooking
import dev.ohs.trustdemo.onesa.data.sync.FakeSyncManager
import dev.ohs.trustdemo.onesa.data.sync.LocalCopy
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.trustkit.StandIn
import dev.ohs.trustdemo.trustkit.TestKeys
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate as Day
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val MAP_ID = "TrustDemoProductToOnesaEpi"

class CardVerificationTest {
  private val nowSeconds = 1_800_000_000L
  private val patientId = "somsak-id"
  private val referral = Referral("referral-1", patientId, "Somsak Sengmany")
  private val gateway = FakeGateway()
  private val repository = InMemorySampleFhirRepository()

  private val service =
    VerificationService(
      repository = repository,
      hcert =
        HcertService(
          local =
            PinnedTrustList(
              name = "Local test list",
              signer = BundledTrustLists::localSigner,
              cache =
                TrustListCache(
                  InMemoryTrustListStorage(),
                  url = "http://trust.gdhcn.example/local-test-list.json",
                  bundled = { resource("/local-test-list.json") },
                ),
            ),
          gdhcnDev = BundledTrustLists.gdhcnDev(InMemoryTrustListStorage()),
        ),
      session = { RegistrationSession("token", "chanthason-id", "lomaru-dhc") },
      httpClient = HttpClient(gateway.engine),
      baseUrl = "http://gateway/fhir",
      refresh = { true },
      clock = { Instant.fromEpochSeconds(nowSeconds) },
    )

  private var refreshes = 0
  private val matching =
    DoseMatchService(
      repository = repository,
      session = { RegistrationSession("token", "chanthason-id", "lomaru-dhc") },
      httpClient = HttpClient(gateway.engine),
      baseUrl = "http://gateway/fhir",
      refresh = {
        refreshes++
        true
      },
      clock = { Instant.fromEpochSeconds(nowSeconds) },
    )

  private val hepB = SignedCards.dvc(StandIn.XXR_CARD, nowSeconds, "vp-hepb-peds-monovalent")
  private val bcg = SignedCards.dvc(StandIn.XXR_CARD, nowSeconds, "vp-bcg-freeze-dried")

  private suspend fun withReportedDoses() {
    reportDose("reported-hepb", "vp-hepb-peds-monovalent", hepB, "2026-03-01")
    reportDose("reported-bcg", "vp-bcg-freeze-dried", bcg, "2026-03-01")
    repository.upsert(
      decode(
        FhirJson.instance
          .parseToJsonElement(
            """{"resourceType":"Patient","id":"$patientId","birthDate":"2026-01-01",
            "name":[{"family":"Sengmany","given":["Somsak"]}]}"""
          )
          .jsonObject
      )
    )
  }

  private suspend fun reportDose(id: String, product: String, card: String, date: String) {
    val dose = reported(id, product, card, date)
    gateway.immunizations[id] = dose
    repository.upsert(decode(dose))
  }

  private suspend fun scan(card: String, text: String = card): ScanOutcome =
    when (val checked = service.check(referral, text)) {
      is ScanCheck.Settled -> checked.outcome
      is ScanCheck.Pending -> service.import(checked.pending)
    }

  @Test
  fun theSameCardScannedTwiceGivesOneVerifiedImmunization() = runTest {
    withReportedDoses()

    assertIs<ScanOutcome.Imported>(scan(hepB))
    assertIs<ScanOutcome.AlreadyImported>(scan(hepB))

    val verified = gateway.immunizations.values.filter { it.flag("primarySource") == true }
    assertEquals(1, verified.size)
    assertEquals(listOf("reported-bcg"), gateway.reportedIds())
    assertEquals(2, gateway.auditEvents.size)
    assertEquals(
      listOf("0", "0"),
      gateway.auditEvents.map { it.getValue("outcome").jsonPrimitive.content },
    )

    gateway.loseNextResponse = true
    assertIs<ScanOutcome.AlreadyImported>(scan(bcg))
    assertIs<ScanOutcome.AlreadyImported>(scan(bcg))
    assertEquals(2, gateway.immunizations.values.count { it.flag("primarySource") == true })
    assertEquals(2, gateway.provenances.size)
    assertEquals(emptyList(), gateway.reportedIds())
  }

  @Test
  fun aCardThatFailsVerificationImportsNothingAndAuditsTheReason() = runTest {
    withReportedDoses()
    val linkCard = SignedCards.dvc(StandIn.XXK_LINK, nowSeconds, "vp-hepb-peds-monovalent")

    val outcome = assertIs<ScanOutcome.Failed>(scan(linkCard))

    assertEquals(
      VerifyStep.TRUST_LOOKUP to FailureReason.KID_NOT_FOUND,
      outcome.step to outcome.reason,
    )
    val kid =
      MessageDigest.getInstance("SHA-256")
        .digest(TestKeys.certificate(StandIn.XXK_LINK).encoded)
        .take(8)
        .joinToString("") { "%02x".format(it) }
    assertEquals(kid, outcome.kid, "the failure names the key the card's header gives")
    assertEquals(1, gateway.transactions.size)
    assertEquals(setOf("AuditEvent"), gateway.transactions.single().map { it.type }.toSet())
    assertEquals(listOf("reported-bcg", "reported-hepb"), gateway.reportedIds().sorted())
    val audit = gateway.auditEvents.single()
    assertEquals("4", audit.getValue("outcome").jsonPrimitive.content)
    val codes =
      audit.getValue("subtype").jsonArray.map {
        it.jsonObject.getValue("code").jsonPrimitive.content
      }
    assertTrue("failed" in codes && "trust-lookup" in codes && "KidNotFound" in codes, "$codes")
  }

  @Test
  fun aVerifiedImportSatisfiesTheJ3aContract() = runTest {
    withReportedDoses()
    scan(hepB)
    scan(bcg)

    val gatewayScope = "Patient/$patientId"
    gateway.provenances.values.forEach {
      assertTrue(gatewayScope in it.references("target"), "the Provenance names the patient")
    }
    val stored =
      mapOf(
        "Immunization" to gateway.immunizations.values.toList(),
        "Provenance" to gateway.provenances.values.toList(),
      )
    val dates = Dates(LocalDate.parse("2026-11-14"))
    for (expectation in expectations.getValue(Step.J3A)) {
      val found = stored.getValue(expectation.query.type).map(::decode)
      assertTrue(expectation.satisfied(found, dates), expectation.label)
    }
  }

  @Test
  fun aCardForAnotherChildImportsNothing() = runTest {
    withReportedDoses()
    repository.upsert(
      decode(
        FhirJson.instance
          .parseToJsonElement(
            """{"resourceType":"Patient","id":"$patientId","birthDate":"2026-02-02"}"""
          )
          .jsonObject
      )
    )

    val outcome = assertIs<ScanOutcome.HolderMismatch>(scan(hepB))

    assertEquals("2026-01-01" to "2026-02-02", outcome.cardBirthDate to outcome.childBirthDate)
    assertEquals(setOf("AuditEvent"), gateway.transactions.single().map { it.type }.toSet())
    assertEquals(0, gateway.immunizations.values.count { it.flag("primarySource") == true })
    assertEquals(listOf("reported-bcg", "reported-hepb"), gateway.reportedIds().sorted())
    val subtypes =
      gateway.auditEvents.single().getValue("subtype").jsonArray.map {
        it.jsonObject.getValue("system").jsonPrimitive.content to
          it.jsonObject.getValue("code").jsonPrimitive.content
      }
    assertTrue(
      "http://ohs.dev/fhir/trust-demo/CodeSystem/TrustDemoScanRefusal" to "holder-mismatch" in
        subtypes,
      "$subtypes",
    )
  }

  @Test
  fun nothingIsWrittenBeforeTheNurseConfirms() = runTest {
    withReportedDoses()

    val pending = assertIs<ScanCheck.Pending>(service.check(referral, hepB)).pending

    assertEquals(0, gateway.transactions.size)
    assertEquals("2026-01-01", pending.holderBirthDate)
    assertEquals("Test Child", pending.holderName)
    assertEquals("Somsak Sengmany", pending.childName)
    assertIs<ScanOutcome.Imported>(service.import(pending))
    assertEquals(1, gateway.transactions.size)
  }

  @Test
  fun anImportFromAnotherDeviceWhileWaitingIsNotRewritten() = runTest {
    withReportedDoses()
    val first = assertIs<ScanCheck.Pending>(service.check(referral, hepB)).pending
    val second = assertIs<ScanCheck.Pending>(service.check(referral, hepB)).pending

    assertIs<ScanOutcome.Imported>(service.import(first))
    assertIs<ScanOutcome.AlreadyImported>(service.import(second))

    assertEquals(setOf("AuditEvent"), gateway.transactions.last().map { it.type }.toSet())
    assertEquals(1, gateway.immunizations.values.count { it.flag("primarySource") == true })
    assertEquals(1, gateway.provenances.size)
  }

  @Test
  fun aPartialBirthDateIsNotAMatch() = runTest {
    withReportedDoses()
    repository.upsert(
      decode(
        FhirJson.instance
          .parseToJsonElement(
            """{"resourceType":"Patient","id":"$patientId","birthDate":"2026-01"}"""
          )
          .jsonObject
      )
    )
    val card = SignedCards.dvc(StandIn.XXR_CARD, nowSeconds, "vp-hepb-peds-monovalent", "2026-01")

    assertIs<ScanOutcome.HolderMismatch>(scan(card))
    assertEquals(0, gateway.immunizations.values.count { it.flag("primarySource") == true })
  }

  @Test
  fun aCardReplacesOnlyItsOwnReportedDose() = runTest {
    withReportedDoses()
    reportDose("reported-hepb-later", "vp-hepb-peds-monovalent", hepB, "2026-04-01")

    scan(hepB)

    assertEquals(listOf("reported-bcg", "reported-hepb-later"), gateway.reportedIds().sorted())
  }

  private suspend fun withNationalMap() {
    val map =
      FhirJson.instance
        .parseToJsonElement(
          """{"resourceType":"ConceptMap","id":"$MAP_ID","status":"draft","url":"${ScanTransactions.EPI_MAP}",
          "group":[{"source":"${ScanTransactions.PRODUCT_SYSTEM}",
          "target":"${ScanTransactions.EPI_SYSTEM}","element":[{"code":"vp-bcg-freeze-dried",
          "target":[{"code":"XXK-EPI-BCG","equivalence":"equivalent"}]}]}]}"""
        )
        .jsonObject
    gateway.conceptMaps[MAP_ID] = map
    repository.upsert(decode(map))
  }

  private suspend fun importedDoses() {
    withReportedDoses()
    withNationalMap()
    scan(hepB)
    scan(bcg)
    repository.upsert(decode(gateway.immunizations.getValue(ScanTransactions.immunizationId(hepB))))
  }

  private val hepBChoice = EpiDose("XXK-EPI-HEPB-BD", "Hepatitis B birth dose")

  @Test
  fun thePickerOffersTheSyncedNationalSchedule() = runTest {
    val schedule = igResource("CodeSystem-TrustDemoOnesaEpiSchedule.json")
    repository.upsert(decode(schedule))
    repository.upsert(decode(igResource("CodeSystem-TrustDemoVaccineProduct.json")))
    val concepts =
      schedule.getValue("concept").jsonArray.map {
        EpiDose(it.jsonObject.text("code"), it.jsonObject.text("display"))
      }

    assertEquals(6, concepts.size)
    assertEquals(concepts, matching.choices())
  }

  private fun igResource(name: String): JsonObject =
    FhirJson.instance.parseToJsonElement(File("../../../ig/resources/$name").readText()).jsonObject

  private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

  @Test
  fun onlyAVerifiedDoseWithNoScheduleCodeNeedsTheMatchPicker() = runTest {
    withReportedDoses()
    withNationalMap()
    assertEquals(emptyList(), matching.unmatched(patientId))

    scan(hepB)
    scan(bcg)
    repository.upsert(decode(gateway.immunizations.getValue(ScanTransactions.immunizationId(hepB))))
    repository.upsert(decode(gateway.immunizations.getValue(ScanTransactions.immunizationId(bcg))))

    assertEquals(
      listOf("vp-hepb-peds-monovalent"),
      matching.unmatched(patientId).map { it.product },
    )
  }

  @Test
  fun anAdjudicationSatisfiesTheJ3bContract() = runTest {
    importedDoses()
    val dose = matching.unmatched(patientId).single()

    assertIs<MatchOutcome.Matched>(matching.confirm(dose, hepBChoice))

    assertEquals(
      1,
      gateway.provenances.values.count { "ConceptMap/$MAP_ID" in it.references("target") },
    )
    val adjudication =
      gateway.provenances.values.single { "ConceptMap/$MAP_ID" in it.references("target") }
    assertEquals(
      setOf("Immunization/${dose.id}", "ConceptMap/$MAP_ID", "Patient/$patientId"),
      adjudication.references("target").toSet(),
    )
    val stored =
      mapOf(
        "Provenance" to gateway.provenances.values.toList(),
        "ConceptMap" to gateway.conceptMaps.values.toList(),
      )
    val dates = Dates(LocalDate.parse("2026-11-14"))
    for (expectation in expectations.getValue(Step.J3B).take(2)) {
      val found = stored.getValue(expectation.query.type).map(::decode)
      assertTrue(expectation.satisfied(found, dates), expectation.label)
    }
    assertEquals(1, refreshes)
    repository.upsert(decode(gateway.immunizations.getValue(dose.id)))
    assertEquals(emptyList(), matching.unmatched(patientId))
  }

  @Test
  fun theIssuerCodingIsKept() = runTest {
    importedDoses()
    val dose = matching.unmatched(patientId).single()
    val before = codings(gateway.immunizations.getValue(dose.id))

    matching.confirm(dose, hepBChoice)

    val after = codings(gateway.immunizations.getValue(dose.id))
    assertTrue(before.all { it in after }, "$before is kept in $after")
    assertEquals(
      listOf(ScanTransactions.EPI_SYSTEM to "XXK-EPI-HEPB-BD"),
      after.filter { it.first == ScanTransactions.EPI_SYSTEM },
    )
    assertEquals(1, after.count { it.first == ScanTransactions.PRODUCT_SYSTEM })
  }

  @Test
  fun aChangedConceptMapIsNotOverwritten() = runTest {
    importedDoses()
    val dose = matching.unmatched(patientId).single()
    val written = gateway.conceptMaps.getValue(MAP_ID)
    gateway.beforeTransaction = { gateway.touch("ConceptMap/$MAP_ID") }
    val transactions = gateway.transactions.size

    assertIs<MatchOutcome.Changed>(matching.confirm(dose, hepBChoice))

    assertEquals(transactions, gateway.transactions.size)
    assertEquals(written, gateway.conceptMaps.getValue(MAP_ID))
    assertEquals(
      0,
      gateway.provenances.values.count { "ConceptMap/$MAP_ID" in it.references("target") },
    )
    assertEquals(1, codings(gateway.immunizations.getValue(dose.id)).size)
    assertEquals(1, refreshes)
  }

  @Test
  fun aMapEntryAddedMeanwhileIsNotOverwritten() = runTest {
    importedDoses()
    val dose = matching.unmatched(patientId).single()
    gateway.conceptMaps[MAP_ID] =
      FhirJson.instance
        .parseToJsonElement(
          gateway.conceptMaps
            .getValue(MAP_ID)
            .toString()
            .replace(
              "\"element\":[",
              "\"element\":[{\"code\":\"vp-hepb-peds-monovalent\",\"target\":[{\"code\":\"XXK-EPI-HEPB-BD\"}]},",
            )
        )
        .jsonObject
    val transactions = gateway.transactions.size

    assertIs<MatchOutcome.Changed>(matching.confirm(dose, hepBChoice))
    assertEquals(transactions, gateway.transactions.size)
  }

  @Test
  fun aLaterHepBCardImportsWithBothCodingsOnceMapped() = runTest {
    importedDoses()
    matching.confirm(matching.unmatched(patientId).single(), hepBChoice)
    repository.upsert(decode(gateway.conceptMaps.getValue(MAP_ID)))
    gateway.immunizations.remove(ScanTransactions.immunizationId(hepB))

    scan(hepB)

    val imported = codings(gateway.immunizations.getValue(ScanTransactions.immunizationId(hepB)))
    assertEquals(
      setOf(ScanTransactions.EPI_SYSTEM, ScanTransactions.PRODUCT_SYSTEM),
      imported.map { it.first }.toSet(),
    )
  }

  private fun visitBooking(
    refresh: suspend () -> Boolean,
    caughtUp: () -> Boolean = { true },
  ): VisitBooking =
    VisitBooking(
      schedule =
        ScheduleService(
          repository,
          {
            SchedulePlans.parse(protocol("TrustDemoDT03"), protocol("TrustDemoOnesaInfantSchedule"))
          },
        ) {
          Day.parse("2026-01-20")
        },
      session = { RegistrationSession("token", "chanthason-id", "lomaru-dhc") },
      httpClient = HttpClient(gateway.engine),
      baseUrl = "http://gateway/fhir",
      refresh = refresh,
      caughtUp = caughtUp,
      clock = { Instant.fromEpochSeconds(nowSeconds) },
    )

  private val booking =
    visitBooking({
      refreshes++
      true
    })

  private fun protocol(name: String): String =
    File("src/commonMain/composeResources/files/protocols/PlanDefinition-$name.json").readText()

  private suspend fun withMappedDosesAndReferral() {
    importedDoses()
    matching.confirm(matching.unmatched(patientId).single(), hepBChoice)
    gateway.immunizations.values.forEach { repository.upsert(decode(it)) }
    gateway.serviceRequests[referral.id] =
      FhirJson.instance
        .parseToJsonElement(
          """{"resourceType":"ServiceRequest","id":"${referral.id}","status":"active","intent":"order",
          "code":{"coding":[{"system":"http://snomed.info/sct","code":"3457005"}]},
          "subject":{"reference":"Patient/$patientId"}}"""
        )
        .jsonObject
  }

  @Test
  fun bookingSatisfiesTheJ3bContract() = runTest {
    withMappedDosesAndReferral()

    val outcome = assertIs<BookingOutcome.Booked>(book())

    assertEquals(3, outcome.doses)
    assertEquals("2026-02-12", outcome.date.toString())
    val recommendation = gateway.recommendations.values.single()
    assertEquals(
      listOf("XXK-EPI-PENTA-1", "XXK-EPI-OPV-1", "XXK-EPI-PCV-1"),
      recommendation.getValue("recommendation").jsonArray.map {
        it.jsonObject
          .getValue("vaccineCode")
          .jsonArray
          .single()
          .jsonObject
          .getValue("coding")
          .jsonArray
          .single()
          .jsonObject
          .text("code")
      },
    )
    val reminder = gateway.tasks.values.single()
    assertEquals(
      "ImmunizationRecommendation/${gateway.recommendations.keys.single()}",
      reminder.getValue("focus").jsonObject.text("reference"),
    )
    assertEquals(
      "2026-02-12" to "2026-02-19",
      reminder.getValue("restriction").jsonObject.getValue("period").jsonObject.let {
        it.text("start") to it.text("end")
      },
    )
    val stored =
      mapOf(
        "Provenance" to gateway.provenances.values.toList(),
        "ConceptMap" to gateway.conceptMaps.values.toList(),
        "ImmunizationRecommendation" to gateway.recommendations.values.toList(),
        "Task" to gateway.tasks.values.toList(),
        "ServiceRequest" to gateway.serviceRequests.values.toList(),
      )
    val dates = Dates(LocalDate.parse("2026-11-14"))
    val contract = expectations.getValue(Step.J3B)
    assertEquals(5, contract.size)
    for (expectation in contract) {
      val found = stored.getValue(expectation.query.type).map(::decode)
      assertTrue(expectation.satisfied(found, dates), expectation.label)
    }
  }

  private suspend fun book() = booking.book(booking.open(referral.id), patientId)

  @Test
  fun bookingWaitsForASyncAfterAFailedRefresh() = runTest {
    withMappedDosesAndReferral()
    var status: SyncJobStatus = SyncJobStatus.Failed()
    val copy = LocalCopy(FakeSyncManager { status })
    val lagging = visitBooking(copy::refreshAfterWrite, copy::caughtUp)
    val open = lagging.open(referral.id)

    assertEquals(false, copy.refreshAfterWrite())
    assertIs<BookingOutcome.SyncFirst>(lagging.book(open, patientId))
    assertEquals(0, gateway.recommendations.size)
    assertEquals("active", gateway.serviceRequests.getValue(referral.id).text("status"))

    status = SyncJobStatus.Succeeded()
    copy.syncNow()
    assertIs<BookingOutcome.Booked>(lagging.book(open, patientId))
  }

  @Test
  fun aMatchedDoseSaysWhenTheListCouldNotBeRefreshed() = runTest {
    importedDoses()
    val failing =
      DoseMatchService(
        repository = repository,
        session = { RegistrationSession("token", "chanthason-id", "lomaru-dhc") },
        httpClient = HttpClient(gateway.engine),
        baseUrl = "http://gateway/fhir",
        refresh = { error("offline") },
        clock = { Instant.fromEpochSeconds(nowSeconds) },
      )

    val outcome = failing.confirm(failing.unmatched(patientId).single(), hepBChoice)

    assertEquals(MatchOutcome.Matched(hepBChoice, listStale = true), outcome)
  }

  private fun reopenReferral() {
    gateway.serviceRequests[referral.id] =
      JsonObject(
        gateway.serviceRequests.getValue(referral.id) + ("status" to JsonPrimitive("active"))
      )
  }

  @Test
  fun bookingTwiceWritesOneRecommendation() = runTest {
    withMappedDosesAndReferral()

    book()
    reopenReferral()
    book()
    reopenReferral()
    repository.upsert(
      decode(
        FhirJson.instance
          .parseToJsonElement(
            """{"resourceType":"Patient","id":"$patientId","birthDate":"2026-01-08"}"""
          )
          .jsonObject
      )
    )
    book()

    assertEquals(1, gateway.recommendations.size)
    assertEquals(1, gateway.tasks.size)
    assertEquals(
      "2026-02-19",
      gateway.tasks.values
        .single()
        .getValue("restriction")
        .jsonObject
        .getValue("period")
        .jsonObject
        .text("start"),
    )
    assertEquals("completed", gateway.serviceRequests.getValue(referral.id).text("status"))
  }

  @Test
  fun aReferralChangedMeanwhileIsNotOverwritten() = runTest {
    withMappedDosesAndReferral()
    val open = booking.open(referral.id)
    gateway.touch("ServiceRequest/${referral.id}")

    assertIs<BookingOutcome.Changed>(booking.book(open, patientId))

    assertEquals(0, gateway.recommendations.size)
    assertEquals("active", gateway.serviceRequests.getValue(referral.id).text("status"))
  }

  @Test
  fun aReferralClosedElsewhereIsNotOverwritten() = runTest {
    withMappedDosesAndReferral()
    val open = booking.open(referral.id)
    gateway.serviceRequests[referral.id] =
      JsonObject(
        gateway.serviceRequests.getValue(referral.id) + ("status" to JsonPrimitive("revoked"))
      )
    gateway.touch("ServiceRequest/${referral.id}")
    assertIs<BookingOutcome.Changed>(booking.book(open, patientId))

    assertIs<BookingOutcome.NoLongerOpen>(book())

    assertEquals(0, gateway.recommendations.size)
    assertEquals("revoked", gateway.serviceRequests.getValue(referral.id).text("status"))
  }

  @Test
  fun bookingIsRefusedWhileAnActionCouldNotBeEvaluated() = runTest {
    withMappedDosesAndReferral()
    val broken =
      VisitBooking(
        schedule =
          ScheduleService(
            repository,
            {
              SchedulePlans.parse(
                protocol("TrustDemoDT03"),
                protocol("TrustDemoOnesaInfantSchedule")
                  .replace(
                    "(%counted.coding.code contains %code).not() and",
                    "(%counted contains and",
                  ),
              )
            },
          ) {
            Day.parse("2026-01-20")
          },
        session = { RegistrationSession("token", "chanthason-id", "lomaru-dhc") },
        httpClient = HttpClient(gateway.engine),
        baseUrl = "http://gateway/fhir",
        refresh = { true },
        caughtUp = { true },
        clock = { Instant.fromEpochSeconds(nowSeconds) },
      )

    assertIs<BookingOutcome.Incomplete>(broken.book(broken.open(referral.id), patientId))

    assertEquals(0, gateway.recommendations.size)
    assertEquals(0, gateway.tasks.size)
  }

  @Test
  fun pentavalentCitesOnlyTheHepBDoseAndTheReminderIsNamedByItsVisit() = runTest {
    withMappedDosesAndReferral()

    book()

    val entries = gateway.recommendations.values.single().getValue("recommendation").jsonArray
    val cited = entries.map { it.jsonObject["supportingImmunization"]?.jsonArray?.size ?: 0 }
    assertEquals(listOf(1, 0, 0), cited)
    assertEquals(
      "Immunization appointment reminder: 6-week doses",
      gateway.tasks.values.single().getValue("code").jsonObject.text("text"),
    )
  }

  private fun codings(dose: JsonObject): List<Pair<String, String>> =
    dose.getValue("vaccineCode").jsonObject.getValue("coding").jsonArray.map {
      it.jsonObject.getValue("system").jsonPrimitive.content to
        it.jsonObject.getValue("code").jsonPrimitive.content
    }

  private fun reported(id: String, product: String, card: String, date: String): JsonObject =
    buildJsonObject {
      put("resourceType", "Immunization")
      put("id", id)
      put("status", "completed")
      put("primarySource", false)
      put(
        "identifier",
        FhirJson.instance.parseToJsonElement(
          """[{"system":"${CertificateFingerprint.SYSTEM}","value":"${CertificateFingerprint.of(card)}"}]"""
        ),
      )
      put("patient", buildJsonObject { put("reference", "Patient/$patientId") })
      put(
        "vaccineCode",
        FhirJson.instance.parseToJsonElement(
          """{"coding":[{"system":"http://smart.who.int/trust-demo/CodeSystem/TrustDemoVaccineProduct","code":"$product"}]}"""
        ),
      )
      put("occurrenceDateTime", date)
    }

  private fun JsonObject.references(name: String): List<String> =
    getValue(name).jsonArray.map { it.jsonObject.getValue("reference").jsonPrimitive.content }

  private fun decode(json: JsonObject): Resource =
    FhirJson.instance.decodeFromJsonElement(Resource.serializer(), json)

  private fun JsonObject.flag(name: String): Boolean? =
    get(name)?.jsonPrimitive?.content?.toBooleanStrictOrNull()

  private fun resource(path: String): String =
    checkNotNull(CardVerificationTest::class.java.getResource(path)) { "missing $path" }.readText()
}

/** The gateway as the app sees it: it applies each transaction and answers reads by id. */
private class FakeGateway {
  class Entry(
    val method: String,
    val url: String,
    val resource: JsonObject?,
    val ifMatch: String? = null,
  ) {
    val type: String
      get() = url.substringBefore('/')
  }

  val immunizations = linkedMapOf<String, JsonObject>()
  val conceptMaps = linkedMapOf<String, JsonObject>()
  val serviceRequests = linkedMapOf<String, JsonObject>()
  val recommendations = linkedMapOf<String, JsonObject>()
  val tasks = linkedMapOf<String, JsonObject>()
  var beforeTransaction: (() -> Unit)? = null
  private val versions = mutableMapOf<String, Int>()
  val provenances = linkedMapOf<String, JsonObject>()
  val auditEvents = mutableListOf<JsonObject>()
  val transactions = mutableListOf<List<Entry>>()
  var loseNextResponse = false

  fun reportedIds(): List<String> =
    immunizations
      .filterValues { it["primarySource"]?.jsonPrimitive?.content == "false" }
      .keys
      .toList()

  /** Another writer's change to [key]: its version moves on. */
  fun touch(key: String) {
    versions[key] = (versions[key] ?: 1) + 1
  }

  private fun versioned(key: String, resource: JsonObject): JsonObject =
    JsonObject(
      resource + ("meta" to buildJsonObject { put("versionId", (versions[key] ?: 1).toString()) })
    )

  val engine = MockEngine { request ->
    val ok = headersOf(HttpHeaders.ContentType, "application/fhir+json")
    if (request.method == HttpMethod.Get) {
      val path = request.url.encodedPath.split('/').takeLast(2)
      val found =
        when (path.first()) {
          "ConceptMap" -> conceptMaps[path.last()]
          "ServiceRequest" -> serviceRequests[path.last()]
          else -> immunizations[path.last()]
        }
      if (found == null) respond("", HttpStatusCode.NotFound, ok)
      else respond(versioned(path.joinToString("/"), found).toString(), HttpStatusCode.OK, ok)
    } else {
      val bundle =
        FhirJson.instance.parseToJsonElement((request.body as TextContent).text).jsonObject
      val entries =
        bundle.getValue("entry").jsonArray.map {
          val entry = it.jsonObject
          val call = entry.getValue("request").jsonObject
          Entry(
            call.getValue("method").jsonPrimitive.content,
            call.getValue("url").jsonPrimitive.content,
            entry["resource"]?.jsonObject,
            call["ifMatch"]?.jsonPrimitive?.content,
          )
        }
      beforeTransaction?.also { beforeTransaction = null }?.invoke()
      val stale =
        entries.any { it.ifMatch != null && it.ifMatch != "W/\"${versions[it.url] ?: 1}\"" }
      if (stale) return@MockEngine respond("", HttpStatusCode.PreconditionFailed, ok)
      transactions += listOf(entries)
      entries.filter { it.method == "PUT" }.forEach { touch(it.url) }
      entries.forEach { entry ->
        val id = entry.url.substringAfter('/')
        when {
          entry.method == "DELETE" -> immunizations.remove(id)
          entry.type == "Immunization" -> immunizations[id] = entry.resource!!
          entry.type == "ConceptMap" -> conceptMaps[id] = entry.resource!!
          entry.type == "ServiceRequest" -> serviceRequests[id] = entry.resource!!
          entry.type == "ImmunizationRecommendation" -> recommendations[id] = entry.resource!!
          entry.type == "Task" -> tasks[id] = entry.resource!!
          entry.type == "Provenance" -> provenances[id] = entry.resource!!
          entry.type == "AuditEvent" -> auditEvents += entry.resource!!
        }
      }
      val lost = loseNextResponse
      loseNextResponse = false
      if (lost) respond("", HttpStatusCode.InternalServerError, ok)
      else
        respond(
          """{"resourceType":"Bundle","type":"transaction-response"}""",
          HttpStatusCode.OK,
          ok,
        )
    }
  }
}
