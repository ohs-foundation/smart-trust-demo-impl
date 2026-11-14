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
package dev.ohs.trustdemo.vhl

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val ACCESS_PROFILE =
  "http://smart.who.int/trust-demo/StructureDefinition/TrustDemoOnesaVhlAccess"
private const val PARTICIPATION = "http://terminology.hl7.org/CodeSystem/v3-ParticipationType"
private const val CLINIC_KID_SYSTEM = "http://ohs.dev/fhir/trust-demo/sid/clinic-kid"
private const val SERVICE_NAME = "Onesa VHL service"

private enum class Interaction(val display: String) {
  MANIFEST("IHE ITI VHL manifest retrieval (ITI-YY5)"),
  DOCUMENT("IHE ITI VHL document retrieval (ITI-68)"),
}

/**
 * The decision table: wrong attempts used up, then withdrawn, expired, wrong passcode, else
 * release. Every decided request is audited on HAPI; a manifest release opens the document to the
 * same certificate for [releaseLifetime].
 */
class ReleaseRule(
  private val hapi: FhirBackend,
  private val passcodes: PasscodeStore,
  private val releases: ReleaseStore,
  private val clock: Clock,
  private val releaseLifetime: Duration = RELEASE_LIFETIME,
) : ReleaseDecision {
  override suspend fun decide(request: ReleaseRequest): ReleaseOutcome =
    when (request) {
      is ReleaseRequest.Manifest -> manifest(request)
      is ReleaseRequest.Document -> document(request)
    }

  private suspend fun manifest(request: ReleaseRequest.Manifest): ReleaseOutcome {
    val list = find("List/${request.listId}") ?: return ReleaseOutcome.Denied
    val consentId = consentOf(list) ?: return ReleaseOutcome.Denied
    val consent = find("Consent/$consentId") ?: return ReleaseOutcome.Denied
    val patientId =
      consent.reference("patient")?.removePrefix("Patient/") ?: return ReleaseOutcome.Denied
    val bundleId = bundleOf(consent)
    val refusal = refusal(consentId, consent, request.passcode)
    if (refusal != null) {
      audit(Interaction.MANIFEST, request.requester, patientId, consentId, refusal = refusal)
      return ReleaseOutcome.Refused(refusal)
    }
    val documents =
      list["entry"]
        ?.jsonArray
        ?.mapNotNull { (it as JsonObject).reference("item")?.removePrefix("DocumentReference/") }
        ?.mapNotNull { find("DocumentReference/$it") }
        .orEmpty()
    val now = clock.instant()
    audit(Interaction.MANIFEST, request.requester, patientId, consentId, bundleId)
    documents.mapNotNull(::binaryOf).forEach {
      releases.record(
        Release(
          request.listId,
          it,
          request.requester.kid,
          patientId,
          consentId,
          bundleId.orEmpty(),
          now,
        )
      )
    }
    return ReleaseOutcome.Manifest(searchset(list, documents))
  }

  private suspend fun document(request: ReleaseRequest.Document): ReleaseOutcome {
    val now = clock.instant()
    val known = releases.latest(request.binaryId, null)
    val binary = find("Binary/${request.binaryId}") ?: return ReleaseOutcome.Denied
    val release =
      releases.latest(request.binaryId, request.requester.kid)?.takeIf {
        Duration.between(it.at, now) < releaseLifetime
      }
    val consent = release?.let { find("Consent/${it.consentId}") }
    val refusal =
      when {
        release == null || consent == null -> null
        consent.text("status") == "inactive" -> Refusal.WITHDRAWN
        endOf(consent).isBefore(now) -> Refusal.EXPIRED
        else -> null
      }
    if (release == null || consent == null || refusal != null) {
      audit(
        Interaction.DOCUMENT,
        request.requester,
        known?.patientId,
        known?.consentId,
        known?.bundleId,
        extra = "Binary/${request.binaryId}",
        refusal = refusal,
        outcomeText = "No current release for this certificate; document not served",
      )
      return refusal?.let(ReleaseOutcome::Refused) ?: ReleaseOutcome.Denied
    }
    audit(
      Interaction.DOCUMENT,
      request.requester,
      release.patientId,
      release.consentId,
      release.bundleId,
      extra = "Binary/${request.binaryId}",
    )
    return ReleaseOutcome.Document(
      binary.text("contentType") ?: "application/octet-stream",
      Base64.getDecoder().decode(binary.text("data").orEmpty()),
    )
  }

  private suspend fun refusal(consentId: String, consent: JsonObject, passcode: String?): Refusal? =
    when {
      passcodes.wrongAttempts(consentId) >= MAX_WRONG_ATTEMPTS -> Refusal.ATTEMPTS_EXHAUSTED
      consent.text("status") == "inactive" -> Refusal.WITHDRAWN
      endOf(consent).isBefore(clock.instant()) -> Refusal.EXPIRED
      else ->
        when (passcodes.check(consentId, passcode.orEmpty())) {
          PasscodeCheck.MATCH -> null
          PasscodeCheck.EXHAUSTED -> Refusal.ATTEMPTS_EXHAUSTED
          PasscodeCheck.WRONG,
          PasscodeCheck.UNKNOWN -> Refusal.WRONG_PASSCODE
        }
    }

  private fun endOf(consent: JsonObject): Instant =
    consent["provision"]?.jsonObject?.get("period")?.jsonObject?.text("end")?.let {
      ShareRequest.instantOf(it, endOfDay = true)
    } ?: Instant.MIN

  private fun consentOf(list: JsonObject): String? =
    list["extension"]
      ?.jsonArray
      ?.map { it.jsonObject }
      ?.firstOrNull { it.text("url") == LIST_CONSENT_EXTENSION }
      ?.reference("valueReference")
      ?.removePrefix("Consent/")

  private fun bundleOf(consent: JsonObject): String? =
    consent["provision"]
      ?.jsonObject
      ?.get("data")
      ?.jsonArray
      ?.firstOrNull()
      ?.jsonObject
      ?.reference("reference")
      ?.removePrefix("Bundle/")

  private fun binaryOf(documentReference: JsonObject): String? =
    documentReference["content"]
      ?.jsonArray
      ?.firstNotNullOfOrNull { it.jsonObject["attachment"]?.jsonObject?.text("url") }
      ?.substringAfterLast('/')

  private fun searchset(list: JsonObject, documents: List<JsonObject>): JsonObject =
    buildJsonObject {
      put("resourceType", "Bundle")
      put("type", "searchset")
      put("total", 1)
      putJsonArray("entry") {
        add(
          buildJsonObject {
            put("fullUrl", "urn:uuid:${list.id()}")
            put("resource", JsonObject(list - "extension"))
            putJsonObject("search") { put("mode", "match") }
          }
        )
        documents.forEach {
          add(
            buildJsonObject {
              put("fullUrl", "urn:uuid:${it.id()}")
              put("resource", it)
              putJsonObject("search") { put("mode", "include") }
            }
          )
        }
      }
    }

  private suspend fun find(path: String): JsonObject? =
    try {
      hapi.read(path)
    } catch (e: BackendRefused) {
      if (e.status == 404) null else throw e
    }

  private suspend fun audit(
    interaction: Interaction,
    requester: Requester,
    patientId: String?,
    consentId: String? = null,
    bundleId: String? = null,
    extra: String? = null,
    refusal: Refusal? = null,
    outcomeText: String? = null,
  ) {
    val released = refusal == null && outcomeText == null
    val id = UUID.randomUUID().toString()
    val event = buildJsonObject {
      put("resourceType", "AuditEvent")
      put("id", id)
      putJsonObject("meta") { putJsonArray("profile") { add(JsonPrimitive(ACCESS_PROFILE)) } }
      putJsonObject("type") {
        put("system", "http://dicom.nema.org/resources/ontology/DCM")
        put("code", "110112")
        put("display", "Query")
      }
      putJsonArray("subtype") {
        add(buildJsonObject { put("display", interaction.display) })
        refusal?.let {
          add(
            buildJsonObject {
              put("system", it.system)
              put("code", it.code)
            }
          )
        }
      }
      put("action", "E")
      put("recorded", clock.instant().toString())
      put("outcome", if (released) "0" else "4")
      put("outcomeDesc", refusal?.text ?: outcomeText ?: "Request released to the receiver")
      putJsonArray("agent") {
        add(
          buildJsonObject {
            putJsonObject("type") {
              putJsonArray("coding") { add(participation("IRCP", "information recipient")) }
            }
            putJsonObject("who") {
              putJsonObject("identifier") {
                put("system", CLINIC_KID_SYSTEM)
                put("value", requester.kid)
              }
              put("display", requester.name)
            }
            put("name", requester.name)
            put("requestor", true)
          }
        )
        add(
          buildJsonObject {
            putJsonObject("type") {
              putJsonArray("coding") { add(participation("CST", "custodian")) }
            }
            putJsonObject("who") { put("display", SERVICE_NAME) }
            put("requestor", false)
          }
        )
      }
      putJsonObject("source") { putJsonObject("observer") { put("display", SERVICE_NAME) } }
      put(
        "entity",
        buildJsonArray {
          patientId?.let {
            add(
              buildJsonObject {
                putJsonObject("what") { put("reference", "Patient/$it") }
                putJsonObject("role") {
                  put("system", "http://terminology.hl7.org/CodeSystem/object-role")
                  put("code", "1")
                  put("display", "Patient")
                }
              }
            )
          }
          listOfNotNull(
              consentId?.let { "Consent/$it" },
              bundleId?.takeIf { released }?.let { "Bundle/$it" },
              extra,
            )
            .forEach { add(buildJsonObject { putJsonObject("what") { put("reference", it) } }) }
        },
      )
    }
    hapi.update("AuditEvent/$id", event)
  }

  private fun participation(code: String, display: String) = buildJsonObject {
    put("system", PARTICIPATION)
    put("code", code)
    put("display", display)
  }

  private fun JsonObject.reference(name: String): String? =
    this[name]?.jsonObject?.text("reference")
}
