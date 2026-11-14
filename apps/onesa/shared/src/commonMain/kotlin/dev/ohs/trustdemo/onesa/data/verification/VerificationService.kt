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
import dev.ohs.trustdemo.hcert.CertificateFingerprint
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
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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

  data class Failed(val step: VerifyStep, val reason: FailureReason) : ScanOutcome
}

/**
 * Verifies each card the nurse scans against the `DEMO-DVC` store and, when it verifies, imports
 * its dose: the verified Immunization, its Provenance and the audit go in one transaction with the
 * delete of the reported dose it replaces. A card that fails writes only its audit.
 *
 * Like [dev.ohs.trustdemo.onesa.data.registration.RegistrationService] it posts straight to the
 * gateway: the delete and the writes must be judged, and land, together. Whether a card was already
 * imported is asked of the local copy and of the gateway, because the local copy lags a sync.
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

  suspend fun scan(referral: Referral, text: String): ScanOutcome {
    val current = session() ?: error("Sign in again to verify a card.")
    val hc1 = text.trim()
    val now = clock()
    val request =
      ScanRequest(hc1, referral.patientId, current.practitionerId, current.organizationId, now)

    val verified =
      when (val result = hcert.verifyCard(hc1, now.epochSeconds)) {
        is VerificationResult.Failed -> return refuse(current, request, result.failure)
        is VerificationResult.Verified -> result.hcert
      }
    val dvc =
      verified.dvc
        ?: return refuse(
          current,
          request,
          VerificationFailure(VerifyStep.PAYLOAD, FailureReason.PAYLOAD_INVALID),
        )
    val dose = "${NewbornRegistration.PRODUCTS[dvc.vx.vp] ?: dvc.vx.vp}, ${dvc.vx.dv}"

    if (alreadyImported(current, referral.patientId, CertificateFingerprint.of(hc1))) {
      post(current, ScanTransactions.alreadyImported(request))
      return ScanOutcome.AlreadyImported(dose)
    }

    val replaced = inbox.reportedDoses(referral.patientId).filter { it.product == dvc.vx.vp }
    val card = VerifiedCard(dvc, verified, hcert.trustListSource(verified.dsc))
    post(current, ScanTransactions.imported(request, card, replaced, epiCodes()))
    replaced.forEach { repository.remove("Immunization", it.id) }
    refresh()
    return ScanOutcome.Imported(dose, replacedReported = replaced.isNotEmpty())
  }

  private suspend fun refuse(
    current: RegistrationSession,
    request: ScanRequest,
    failure: VerificationFailure,
  ): ScanOutcome {
    post(current, ScanTransactions.refused(request, failure))
    return ScanOutcome.Failed(failure.step, failure.reason)
  }

  private suspend fun alreadyImported(
    current: RegistrationSession,
    patientId: String,
    fingerprint: String,
  ): Boolean {
    val local =
      repository.all("Immunization").map { it.toJson() }.any { it.isVerifiedImportOf(fingerprint) }
    if (local) return true

    val reply =
      httpClient.get("$baseUrl/Immunization") {
        bearerAuth(current.accessToken)
        parameter("patient", patientId)
        parameter("identifier", "${CertificateFingerprint.SYSTEM}|$fingerprint")
        // HAPI serves a repeated search from its cache for a minute, and the gateway drops
        // Cache-Control; a bound that moves with the clock keeps the search from being repeated.
        parameter("_lastUpdated", "le${clock()}")
      }
    check(reply.status.isSuccess()) {
      "The gateway could not say whether the card was imported (HTTP ${reply.status.value})."
    }
    return json
      .parseToJsonElement(reply.bodyAsText())
      .jsonObject["entry"]
      ?.jsonArray
      .orEmpty()
      .any { it.jsonObject.getValue("resource").jsonObject.isVerifiedImportOf(fingerprint) }
  }

  /**
   * The dose a card was reported as at J2 carries the same fingerprint; only a verified one counts.
   */
  private fun JsonObject.isVerifiedImportOf(fingerprint: String): Boolean =
    this["primarySource"]?.jsonPrimitive?.contentOrNull == "true" &&
      this["identifier"]?.jsonArray.orEmpty().any {
        it.jsonObject["system"]?.jsonPrimitive?.contentOrNull == CertificateFingerprint.SYSTEM &&
          it.jsonObject["value"]?.jsonPrimitive?.contentOrNull == fingerprint
      }

  private suspend fun epiCodes(): Map<String, String> =
    repository
      .all("ConceptMap")
      .map { it.toJson() }
      .filter { it["url"]?.jsonPrimitive?.contentOrNull == ScanTransactions.EPI_MAP }
      .flatMap { it["group"]?.jsonArray.orEmpty() }
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
