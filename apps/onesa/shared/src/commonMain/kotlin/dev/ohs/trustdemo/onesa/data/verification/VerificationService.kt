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

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.hcert.DvcFhirMapper
import dev.ohs.trustdemo.hcert.DvcMin
import dev.ohs.trustdemo.hcert.FailureReason
import dev.ohs.trustdemo.hcert.VerificationFailure
import dev.ohs.trustdemo.hcert.VerificationResult
import dev.ohs.trustdemo.hcert.VerifyStep
import dev.ohs.trustdemo.onesa.data.hcert.HcertService
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a scan came to, for S4 to show. */
sealed interface ScanOutcome {
  /** The card verified and its dose is now on the patient's record as a verified Immunization. */
  data class Imported(val dose: String, val replacedReported: Boolean) : ScanOutcome

  /** The card verified, but its dose was imported by an earlier scan. */
  data class AlreadyImported(val dose: String) : ScanOutcome

  /** The card verified, but its dose is on another child's record. */
  data class ImportedForAnotherChild(val dose: String) : ScanOutcome

  /** The card verified, but its holder was born on another day than the child. */
  data class HolderMismatch(val cardBirthDate: String, val childBirthDate: String?) : ScanOutcome

  data class Failed(val step: VerifyStep, val reason: FailureReason) : ScanOutcome
}

/** A verified card waiting for the nurse to say it is the child's; nothing is written for it. */
class PendingImport
internal constructor(
  internal val referral: Referral,
  val hc1: String,
  internal val card: VerifiedCard,
  val holderName: String,
  val holderBirthDate: String,
  val childName: String,
  val dose: String,
)

/** What checking a scanned card found: an outcome already settled, or a card to confirm. */
sealed interface ScanCheck {
  data class Settled(val outcome: ScanOutcome) : ScanCheck

  data class Pending(val pending: PendingImport) : ScanCheck
}

/**
 * Verifies each card the nurse scans against the `DEMO-DVC` store and, when it verifies for the
 * referred child, imports its dose once the nurse confirms: the verified Immunization, its
 * Provenance and the audit go in one transaction with the delete of the reported dose it replaces.
 * Every other scan writes only its audit.
 *
 * The Immunization and its Provenance take their ids from the card, so importing a card twice, or
 * from two devices, rewrites one record; the id is read before the card is offered for import. Like
 * [dev.ohs.trustdemo.onesa.data.registration.RegistrationService] it posts straight to the gateway:
 * the delete and the writes must be judged, and land, together.
 */
class VerificationService(
  private val repository: FhirRepository,
  private val hcert: HcertService,
  private val session: () -> RegistrationSession?,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val refresh: suspend () -> Unit,
  private val clock: () -> Instant = Clock.System::now,
) {
  private val json = FhirJson.instance
  private val inbox = ReferralInbox(repository)

  suspend fun check(referral: Referral, text: String): ScanCheck {
    val current = session() ?: error("Sign in again to verify a card.")
    val hc1 = text.trim()
    val request = request(current, referral, hc1)

    val verified =
      when (val result = hcert.verifyCard(hc1, request.now.epochSeconds)) {
        is VerificationResult.Failed -> return settled(refuse(current, request, result.failure))
        is VerificationResult.Verified -> result.hcert
      }
    val dvc =
      verified.dvc
        ?: return settled(
          refuse(
            current,
            request,
            VerificationFailure(VerifyStep.PAYLOAD, FailureReason.PAYLOAD_INVALID),
          )
        )
    val dose = "${NewbornRegistration.PRODUCTS[dvc.vx.vp] ?: dvc.vx.vp}, ${dvc.vx.dv}"

    val existing = readImmunization(current, ScanTransactions.immunizationId(hc1))
    if (existing != null) {
      return if (existing.reference("patient") == "Patient/${referral.patientId}") {
        purgeReplacedLocally(current, referral, dvc)
        post(current, ScanTransactions.alreadyImported(request))
        settled(ScanOutcome.AlreadyImported(dose))
      } else {
        post(current, ScanTransactions.refused(request, Refusal.ImportedForAnotherChild))
        settled(ScanOutcome.ImportedForAnotherChild(dose))
      }
    }

    val child = inbox.child(referral.patientId)
    if (child.birthDate != dvc.dob) {
      post(current, ScanTransactions.refused(request, Refusal.HolderMismatch))
      return settled(ScanOutcome.HolderMismatch(dvc.dob, child.birthDate))
    }
    val card = VerifiedCard(dvc, verified, hcert.trustListSource(verified.dsc))
    val holder = DvcFhirMapper.humanName(dvc.n).text?.value ?: dvc.n
    return ScanCheck.Pending(PendingImport(referral, hc1, card, holder, dvc.dob, child.name, dose))
  }

  suspend fun import(pending: PendingImport): ScanOutcome {
    val current = session() ?: error("Sign in again to verify a card.")
    val request = request(current, pending.referral, pending.hc1)
    val replaced =
      inbox.reportedDoses(pending.referral.patientId).filter { it.isDoseOf(pending.card.dvc) }
    post(current, ScanTransactions.imported(request, pending.card, replaced, epiCodes()))
    replaced.forEach { repository.remove("Immunization", it.id) }
    refresh()
    return ScanOutcome.Imported(pending.dose, replacedReported = replaced.isNotEmpty())
  }

  private fun request(current: RegistrationSession, referral: Referral, hc1: String) =
    ScanRequest(hc1, referral.patientId, current.practitionerId, current.organizationId, clock())

  private fun settled(outcome: ScanOutcome) = ScanCheck.Settled(outcome)

  private suspend fun refuse(
    current: RegistrationSession,
    request: ScanRequest,
    failure: VerificationFailure,
  ): ScanOutcome {
    post(current, ScanTransactions.refused(request, failure))
    return ScanOutcome.Failed(failure.step, failure.reason)
  }

  /** The Immunization under [id], or null when the gateway has none in this clinic's scope. */
  private suspend fun readImmunization(current: RegistrationSession, id: String): JsonObject? {
    val reply = httpClient.get("$baseUrl/Immunization/$id") { bearerAuth(current.accessToken) }
    if (reply.status == HttpStatusCode.NotFound || reply.status == HttpStatusCode.Forbidden)
      return null
    check(reply.status.isSuccess()) {
      "The gateway could not say whether the card was imported (HTTP ${reply.status.value})."
    }
    return json.parseToJsonElement(reply.bodyAsText()).jsonObject
  }

  /** An import that committed but never reached this device leaves its reported doses behind. */
  private suspend fun purgeReplacedLocally(
    current: RegistrationSession,
    referral: Referral,
    dvc: DvcMin,
  ) {
    inbox
      .reportedDoses(referral.patientId)
      .filter { it.isDoseOf(dvc) && readImmunization(current, it.id) == null }
      .forEach { repository.remove("Immunization", it.id) }
  }

  private fun JsonObject.reference(name: String): String? =
    this[name]?.jsonObject?.get("reference")?.jsonPrimitive?.contentOrNull

  private suspend fun epiCodes(): Map<String, String> =
    repository
      .all("ConceptMap")
      .map { it.toJson() }
      .filter { it["url"]?.jsonPrimitive?.contentOrNull == ScanTransactions.EPI_MAP }
      .flatMap { it["group"]?.jsonArray.orEmpty() }
      .filter {
        it.jsonObject["source"]?.jsonPrimitive?.contentOrNull == ScanTransactions.PRODUCT_SYSTEM &&
          it.jsonObject["target"]?.jsonPrimitive?.contentOrNull == ScanTransactions.EPI_SYSTEM
      }
      .flatMap { it.jsonObject["element"]?.jsonArray.orEmpty() }
      .mapNotNull { element ->
        val target = element.jsonObject["target"]?.jsonArray?.firstOrNull()?.jsonObject?.get("code")
        element.jsonObject["code"]?.jsonPrimitive?.contentOrNull?.let { product ->
          target?.jsonPrimitive?.contentOrNull?.let { product to it }
        }
      }
      .toMap()

  private suspend fun post(current: RegistrationSession, transaction: JsonObject) {
    val reply =
      httpClient.post(baseUrl) {
        bearerAuth(current.accessToken)
        contentType(ContentType("application", "fhir+json"))
        setBody(json.encodeToString(JsonObject.serializer(), transaction))
      }
    check(reply.status.isSuccess()) {
      "The scan was not accepted (HTTP ${reply.status.value}): ${reply.bodyAsText().take(300)}"
    }
  }

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject
}
