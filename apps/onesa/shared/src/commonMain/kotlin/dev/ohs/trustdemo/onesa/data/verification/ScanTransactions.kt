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
import dev.ohs.fhir.model.r4.Provenance
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.trustdemo.hcert.CertificateFingerprint
import dev.ohs.trustdemo.hcert.DvcFhirMapper
import dev.ohs.trustdemo.hcert.DvcMappingOptions
import dev.ohs.trustdemo.hcert.DvcMin
import dev.ohs.trustdemo.hcert.FailureReason
import dev.ohs.trustdemo.hcert.VerificationEvidence
import dev.ohs.trustdemo.hcert.VerificationFailure
import dev.ohs.trustdemo.hcert.VerifiedHcert
import dev.ohs.trustdemo.hcert.VerifyStep
import dev.ohs.trustdemo.onesa.data.hcert.TrustDomain
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.generateId
import dev.ohs.trustdemo.onesa.util.FhirJson
import dev.ohs.trustdemo.onesa.util.createEntry
import dev.ohs.trustdemo.onesa.util.deleteEntry
import dev.ohs.trustdemo.onesa.util.profile
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A dose Vilay reported at J2, which a verified card of the same product replaces; [origin] is
 * where the card says it was given.
 */
data class ReportedDose(
  val id: String,
  val product: String,
  val date: String? = null,
  val lot: String? = null,
  val origin: String? = null,
) {
  /** Whether this is the dose [dvc] documents: the same product given on the same day. */
  fun isDoseOf(dvc: DvcMin): Boolean =
    product == dvc.vx.vp && date != null && date == dvc.vx.dv.substringBefore('T')
}

/** Why a verified card was still not imported, in `TrustDemoScanRefusal`. */
enum class Refusal(val code: String, val description: String) {
  HolderMismatch(
    "holder-mismatch",
    "Card verified; its holder's birth date differs from the child's",
  ),
  ImportedForAnotherChild(
    "imported-for-another-child",
    "Card verified; already imported for another child",
  ),
}

/** One scan of one card at the clinic, for the patient the referral is about. */
data class ScanRequest(
  val hc1: String,
  val patientId: String,
  val practitionerId: String,
  val organizationId: String,
  val now: Instant,
  val newId: () -> String = ::generateId,
)

/** A card that passed DT.01: what it says, who signed it, and the list the signer's key is on. */
data class VerifiedCard(val dvc: DvcMin, val hcert: VerifiedHcert, val trustListSource: String)

/** The IG's code for a verification step, as `TrustDemoVerificationStep` spells it. */
val VerifyStep.code: String
  get() = name.lowercase().replace('_', '-')

/** The IG's code for a failure reason, as `TrustDemoVerificationFailureReason` spells it. */
val FailureReason.code: String
  get() =
    name.split('_').joinToString("") { word -> word.lowercase().replaceFirstChar(Char::titlecase) }

/**
 * The transaction one scan sends: every scan writes its AuditEvent, and a verified card that is new
 * to the patient also writes its Immunization and Provenance and deletes the reported dose it
 * replaces.
 *
 * The IG profiles AuditEvent only for the VHL release; the scan's audit is a plain AuditEvent. Its
 * subtypes carry the DT.01 result, step and failure reason in the IG's own code systems, so a count
 * of outcomes (IND01) reads them straight off.
 */
object ScanTransactions {
  const val PRODUCT_SYSTEM = "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVaccineProduct"
  const val EPI_SYSTEM = "http://smart.who.int/trust-demo/CodeSystem/TrustDemoOnesaEpiSchedule"
  const val CVX_SYSTEM = "http://hl7.org/fhir/sid/cvx"
  const val REFUSAL_SYSTEM = "http://ohs.dev/fhir/trust-demo/CodeSystem/TrustDemoScanRefusal"
  const val EPI_MAP = "http://smart.who.int/trust-demo/ConceptMap/TrustDemoProductToOnesaEpi"

  private const val IG = "http://smart.who.int/trust-demo/CodeSystem"
  private const val AUDIT_TYPE = "http://terminology.hl7.org/CodeSystem/audit-event-type"
  private const val OBJECT_ROLE = "http://terminology.hl7.org/CodeSystem/object-role"

  private val json = FhirJson.instance

  /** The Immunization's id, fixed by the card so that importing it twice writes one record. */
  fun immunizationId(hc1: String): String = idFrom(CertificateFingerprint.of(hc1))

  private fun provenanceId(hc1: String): String =
    idFrom(CertificateFingerprint.of("provenance:${CertificateFingerprint.of(hc1)}"))

  internal fun idFrom(digest: String): String {
    val hex = digest.take(32).toCharArray()
    hex[12] = '5'
    hex[16] = "89ab"[hex[16].digitToInt(16) and 3]
    val text = hex.concatToString()
    return "${text.take(8)}-${text.substring(8, 12)}-${text.substring(12, 16)}-" +
      "${text.substring(16, 20)}-${text.substring(20)}"
  }

  /**
   * The verified Immunization with both codings (the Onesan schedule code from [epiCodes] when the
   * national map has one, and the issuer's product), its Provenance, the audit, and a delete for
   * each of [reported] that [card] replaces.
   */
  fun imported(
    request: ScanRequest,
    card: VerifiedCard,
    reported: List<ReportedDose>,
    epiCodes: Map<String, String>,
    scheduleDisplays: Map<String, String>,
    overrideReason: String? = null,
  ): JsonObject {
    val dvc = card.dvc
    val immunizationId = immunizationId(request.hc1)
    val unverified =
      DvcFhirMapper.map(
          dvc,
          DvcMappingOptions(
            patientId = request.patientId,
            immunizationId = immunizationId,
            certificateFingerprint = CertificateFingerprint.of(request.hc1),
            recordedAtEpochSeconds = request.now.epochSeconds,
          ),
        )
        .immunization
    val evidence =
      VerificationEvidence(
        issuer = card.hcert.iss,
        dscKid = card.hcert.dsc.kid,
        dscCountry = card.hcert.dsc.country,
        trustListUrl = card.trustListSource,
        verifier =
          Reference(reference = FhirString(value = "Practitioner/${request.practitionerId}")),
        verifiedAtEpochSeconds = request.now.epochSeconds,
      )
    val (verified, provenance) = DvcFhirMapper.markVerified(unverified, evidence)

    return transaction(
      createEntry(
        "Immunization",
        immunizationId,
        immunization(verified, dvc.vx.vp, epiCodes, scheduleDisplays),
      ),
      createEntry(
        "Provenance",
        provenanceId(request.hc1),
        provenance(provenance, request.patientId, card, reported, overrideReason),
      ),
      createEntry("AuditEvent", request.newId(), audit(request, Result.Imported(overrideReason))),
      *reported.map { deleteEntry("Immunization/${it.id}") }.toTypedArray(),
    )
  }

  fun alreadyImported(request: ScanRequest): JsonObject =
    transaction(createEntry("AuditEvent", request.newId(), audit(request, Result.AlreadyImported)))

  fun refused(request: ScanRequest, refusal: Refusal): JsonObject =
    transaction(
      createEntry("AuditEvent", request.newId(), audit(request, Result.Declined(refusal)))
    )

  fun refused(request: ScanRequest, failure: VerificationFailure): JsonObject =
    transaction(createEntry("AuditEvent", request.newId(), audit(request, Result.Refused(failure))))

  private fun transaction(vararg entries: JsonObject): JsonObject = buildJsonObject {
    put("resourceType", "Bundle")
    put("type", "transaction")
    putJsonArray("entry") { entries.forEach { add(it) } }
  }

  private fun immunization(
    verified: Immunization,
    product: String,
    epiCodes: Map<String, String>,
    scheduleDisplays: Map<String, String>,
  ): JsonObject {
    val resource = json.encodeToJsonElement(Immunization.serializer(), verified).jsonObject
    val named = NewbornRegistration.PRODUCTS[product]
    val codings =
      listOfNotNull(
        epiCodes[product]?.let { coding(EPI_SYSTEM, it, scheduleDisplays[it]) },
        named?.let { coding(PRODUCT_SYSTEM, product, it) },
        NewbornRegistration.CVX[product]?.let { (code, display) ->
          coding(CVX_SYSTEM, code, display)
        },
      )
    val text = epiCodes[product]?.let(scheduleDisplays::get) ?: named
    val vaccineCode =
      if (named == null) resource.getValue("vaccineCode")
      else
        JsonObject(
          resource.getValue("vaccineCode").jsonObject +
            mapOf("coding" to JsonArray(codings), "text" to JsonPrimitive(text))
        )
    return JsonObject(
      resource +
        mapOf("meta" to profile("TrustDemoOnesaImmunization"), "vaccineCode" to vaccineCode)
    )
  }

  /**
   * The patient is also a target: the gateway scopes a Provenance to a clinic by the Patient it
   * targets, and refuses one that names none.
   */
  private fun provenance(
    provenance: Provenance,
    patientId: String,
    card: VerifiedCard,
    replaced: List<ReportedDose>,
    overrideReason: String?,
  ): JsonObject {
    val resource = json.encodeToJsonElement(Provenance.serializer(), provenance).jsonObject
    val patient = buildJsonObject { put("reference", "Patient/$patientId") }
    val removals =
      replaced.map {
        buildJsonObject {
          put("role", "removal")
          putJsonObject("what") { put("reference", "Immunization/${it.id}") }
        }
      }
    val reason =
      overrideReason?.let { "reason" to JsonArray(listOf(buildJsonObject { put("text", it) })) }
    return JsonObject(
      resource +
        mapOf(
          "meta" to profile("TrustDemoOnesaDvcVerification"),
          "target" to JsonArray(resource.getValue("target").jsonArray + patient),
          "activity" to buildJsonObject { put("text", activity(card)) },
          "entity" to JsonArray(resource.getValue("entity").jsonArray + removals),
        ) +
        listOfNotNull(reason)
    )
  }

  private fun activity(card: VerifiedCard): String {
    val dsc = card.hcert.dsc
    val standIn = dsc.domain == TrustDomain.Dvc.code
    val list = if (standIn) "${TrustDomain.Dvc.code} trust list" else "GDHCN DEV trust list"
    val signer = if (standIn) "${dsc.participant} card stand-in key" else "key"
    return "DT.01: card verified against the $list ${card.trustListSource}, " +
      "signed by the $signer ${dsc.kid}; its dose imported and the reported dose removed"
  }

  private sealed interface Result {
    data class Imported(val overrideReason: String?) : Result

    data object AlreadyImported : Result

    data class Refused(val failure: VerificationFailure) : Result

    data class Declined(val refusal: Refusal) : Result
  }

  private fun audit(request: ScanRequest, result: Result): JsonObject {
    val refused = (result as? Result.Refused)?.failure
    val declined = (result as? Result.Declined)?.refusal
    val subtypes =
      listOfNotNull(
        coding(
          "$IG/TrustDemoVerificationResult",
          if (refused == null && declined == null) "verified" else "failed",
          null,
        ),
        refused?.let { coding("$IG/TrustDemoVerificationStep", it.step.code, null) },
        refused?.let { coding("$IG/TrustDemoVerificationFailureReason", it.reason.code, null) },
        declined?.let { coding(REFUSAL_SYSTEM, it.code, null) },
      )
    return buildJsonObject {
      put("resourceType", "AuditEvent")
      put("type", coding(AUDIT_TYPE, "rest", "RESTful Operation"))
      put("subtype", JsonArray(subtypes))
      put("action", "E")
      put("recorded", request.now.toString())
      put("outcome", if (refused == null && declined == null) "0" else "4")
      put(
        "outcomeDesc",
        when (result) {
          is Result.Imported ->
            "Card verified; its dose imported" +
              result.overrideReason
                ?.let { "; name differs from the child's, nurse's reason: $it" }
                .orEmpty()
          Result.AlreadyImported -> "Card verified; already imported, nothing written"
          is Result.Declined -> result.refusal.description
          is Result.Refused ->
            "Verification failed at ${result.failure.step.code}: ${result.failure.reason.code}"
        },
      )
      putJsonArray("agent") {
        add(
          buildJsonObject {
            putJsonObject("who") { put("reference", "Practitioner/${request.practitionerId}") }
            put("requestor", true)
          }
        )
      }
      putJsonObject("source") {
        putJsonObject("observer") { put("reference", "Organization/${request.organizationId}") }
      }
      putJsonArray("entity") {
        add(
          buildJsonObject {
            putJsonObject("what") { put("reference", "Patient/${request.patientId}") }
            put("role", coding(OBJECT_ROLE, "1", "Patient"))
          }
        )
        add(
          buildJsonObject {
            putJsonObject("what") {
              putJsonObject("identifier") {
                put("system", CertificateFingerprint.SYSTEM)
                put("value", CertificateFingerprint.of(request.hc1))
              }
            }
          }
        )
      }
    }
  }

  private fun coding(system: String, code: String, display: String?): JsonObject = buildJsonObject {
    put("system", system)
    put("code", code)
    display?.let { put("display", it) }
  }
}

/** The step's name for the screen: its code with the dashes as spaces. */
val VerifyStep.display: String
  get() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)

val FailureReason.display: String
  get() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)
