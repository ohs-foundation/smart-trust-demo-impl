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

import java.net.URLEncoder
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Points a share's manifest List at the Consent that governs its release. */
internal const val LIST_CONSENT_EXTENSION =
  "http://ohs.dev/fhir/trust-demo/StructureDefinition/list-consent"
private const val IPS_BASE = "http://smart.who.int/trust-demo"
private const val LOINC = "http://loinc.org"
private const val PHW_CONSENT =
  "http://smart.who.int/trust-phw/StructureDefinition/PHWExplicitConsent"
private const val PORTAL_ORGANIZATION = "Organization/TrustDemoOnesaPortalOrg"
private val EMPTY_REASON_SYSTEM = "http://terminology.hl7.org/CodeSystem/list-empty-reason"
private val EMPTY_SECTIONS =
  listOf(
    Triple("48765-2", "Allergies and adverse reactions Document", "Allergies and intolerances"),
    Triple("10160-0", "History of Medication use Narrative", "Medication summary"),
    Triple("11450-4", "Problem list - Reported", "Problems"),
  )

/** The records of one share, before its IPS is encrypted. */
class Share(
  val patient: JsonObject,
  val consent: JsonObject,
  val ips: JsonObject,
  val binaryId: String,
  val documentReferenceId: String,
  val listId: String,
)

/** The FHIR records of one share: the consent, the IPS, its encrypted copy and the manifest. */
class Records(private val publicUrl: String, private val now: Instant) {
  fun prepare(
    caller: String,
    patient: JsonObject,
    immunizations: List<JsonObject>,
    request: ShareRequest,
  ): Share {
    val ownImmunizations =
      immunizations.filter {
        it["patient"]?.jsonObject?.text("reference") == "Patient/${patient.id()}"
      }
    val bundleId = newId()
    val consent = consent(newId(), caller, patient.id(), bundleId, request)
    return Share(
      patient = patient,
      consent = consent,
      ips = ips(bundleId, patient, ownImmunizations, consent),
      binaryId = newId(),
      documentReferenceId = newId(),
      listId = newId(),
    )
  }

  /** The one HAPI transaction for every share, each with its IPS encrypted to its JWE. */
  fun transaction(shares: List<Pair<Share, String>>): JsonObject = buildJsonObject {
    put("resourceType", "Bundle")
    put("type", "transaction")
    putJsonArray("entry") {
      shares.forEach { (share, jwe) ->
        val patientId = share.patient.id()
        add(entry(share.consent))
        add(entry(share.ips))
        add(entry(binary(share.binaryId, jwe)))
        add(entry(documentReference(share.documentReferenceId, patientId, share.binaryId)))
        add(entry(manifest(share.listId, patientId, share.documentReferenceId, share.consent.id())))
      }
    }
  }

  /** The manifest search the link points at; the clinic's server answers it from [listId]. */
  fun manifestUrl(listId: String, patient: JsonObject): String {
    val identifier =
      patient["identifier"]?.jsonArray?.firstOrNull()?.jsonObject?.let {
        "${it.text("system")}|${it.text("value")}"
      } ?: "urn:ietf:rfc:3986|Patient/${patient.id()}"
    return "$publicUrl/vhl/List/_search?_id=$listId&code=folder&status=current" +
      "&patient.identifier=${identifier.encodeUrl()}"
  }

  private fun entry(resource: JsonObject) = buildJsonObject {
    put("fullUrl", "urn:uuid:${resource.id()}")
    put("resource", resource)
    putJsonObject("request") {
      put("method", "PUT")
      put("url", "${resource.text("resourceType")}/${resource.id()}")
    }
  }

  private fun consent(
    id: String,
    caller: String,
    patientId: String,
    bundleId: String,
    request: ShareRequest,
  ) = buildJsonObject {
    put("resourceType", "Consent")
    put("id", id)
    putJsonObject("meta") { putJsonArray("profile") { add(JsonPrimitive(PHW_CONSENT)) } }
    put("status", "active")
    putJsonObject("scope") {
      putJsonArray("coding") {
        add(
          coding(
            "http://terminology.hl7.org/CodeSystem/consentscope",
            "patient-privacy",
            "Privacy Consent",
          )
        )
      }
    }
    putJsonArray("category") {
      add(codeable(coding(LOINC, "59284-0", "Consent Document")))
      add(codeable(coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "OPTIN", "opt-in")))
    }
    put("patient", reference("Patient/$patientId"))
    put("dateTime", now.toString())
    putJsonArray("performer") { add(reference("Patient/$caller")) }
    putJsonArray("organization") { add(reference(PORTAL_ORGANIZATION)) }
    putJsonObject("sourceAttachment") {
      put("contentType", "application/json")
      put("title", "Explicit consent confirmed on the family wallet when the link was created")
    }
    putJsonObject("policyRule") {
      putJsonArray("coding") {
        add(
          buildJsonObject {
            put("system", "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlConsent")
            put("version", "0.1.0")
            put("code", "CONSENT-VHL-TREATMENT")
            put("display", "Consent to read the summary for treatment")
          }
        )
      }
    }
    putJsonObject("provision") {
      put("type", "permit")
      putJsonObject("period") {
        put("start", request.periodStart)
        put("end", request.periodEnd)
      }
      putJsonArray("purpose") {
        add(coding("http://terminology.hl7.org/CodeSystem/v3-ActReason", "TREAT", "treatment"))
      }
      putJsonArray("data") {
        add(
          buildJsonObject {
            put("meaning", "instance")
            put("reference", reference("Bundle/$bundleId"))
          }
        )
      }
    }
  }

  private fun ips(
    id: String,
    patient: JsonObject,
    immunizations: List<JsonObject>,
    consent: JsonObject,
  ): JsonObject {
    val composition = composition(newId(), patient, immunizations, consent.id())
    return buildJsonObject {
      put("resourceType", "Bundle")
      put("id", id)
      putJsonObject("identifier") {
        put("system", "urn:ietf:rfc:3986")
        put("value", "urn:uuid:$id")
      }
      put("type", "document")
      put("timestamp", now.toString())
      put(
        "entry",
        buildJsonArray {
          (listOf(composition, patient) + immunizations + consent).forEach { resource ->
            add(
              buildJsonObject {
                put("fullUrl", "$IPS_BASE/${resource.text("resourceType")}/${resource.id()}")
                put("resource", resource)
              }
            )
          }
        },
      )
    }
  }

  private fun composition(
    id: String,
    patient: JsonObject,
    immunizations: List<JsonObject>,
    consentId: String,
  ) = buildJsonObject {
    put("resourceType", "Composition")
    put("id", id)
    put("status", "final")
    put("type", codeable(coding(LOINC, "60591-5", "Patient summary Document")))
    put("subject", reference("Patient/${patient.id()}"))
    put("date", now.toString())
    putJsonArray("author") {
      add(buildJsonObject { put("display", "Onesa national health portal (Country B, XXK)") })
    }
    put("title", "Patient summary: ${displayName(patient)}")
    putJsonArray("section") {
      EMPTY_SECTIONS.forEach { (code, display, title) ->
        add(section(title, coding(LOINC, code, display), emptyReason("unavailable")))
      }
      add(
        section(
          "Immunizations",
          coding(LOINC, "11369-6", "History of Immunization Narrative"),
          if (immunizations.isEmpty()) emptyReason("nilknown") else null,
          immunizations.map { "Immunization/${it.id()}" },
        )
      )
      add(
        section(
          "Advance directives: consent",
          coding(LOINC, "42348-3", "Advance directives"),
          null,
          listOf("Consent/$consentId"),
        )
      )
    }
  }

  private fun section(
    title: String,
    code: JsonObject,
    emptyReason: JsonObject?,
    entries: List<String> = emptyList(),
  ) = buildJsonObject {
    put("title", title)
    put("code", codeable(code))
    emptyReason?.let { put("emptyReason", it) }
    if (entries.isNotEmpty()) {
      put("entry", JsonArray(entries.map { reference(it) }))
    }
  }

  private fun emptyReason(code: String) =
    codeable(
      coding(EMPTY_REASON_SYSTEM, code, if (code == "nilknown") "Nil Known" else "Unavailable")
    )

  private fun binary(id: String, jwe: String) = buildJsonObject {
    put("resourceType", "Binary")
    put("id", id)
    put("contentType", "application/jose")
    put("data", Base64.getEncoder().encodeToString(jwe.toByteArray()))
  }

  private fun documentReference(id: String, patientId: String, binaryId: String) = buildJsonObject {
    put("resourceType", "DocumentReference")
    put("id", id)
    put("status", "current")
    put("type", codeable(coding(LOINC, "60591-5", "Patient summary Document")))
    put("subject", reference("Patient/$patientId"))
    put("date", now.toString())
    putJsonArray("content") {
      add(
        buildJsonObject {
          putJsonObject("attachment") {
            put("contentType", "application/jose")
            put("url", "$publicUrl/vhl/Binary/$binaryId")
            put("title", "Immunization summary")
          }
        }
      )
    }
  }

  private fun manifest(
    id: String,
    patientId: String,
    documentReferenceId: String,
    consentId: String,
  ) = buildJsonObject {
    put("resourceType", "List")
    put("id", id)
    putJsonArray("extension") {
      add(
        buildJsonObject {
          put("url", LIST_CONSENT_EXTENSION)
          put("valueReference", reference("Consent/$consentId"))
        }
      )
    }
    put("status", "current")
    put("mode", "working")
    put("title", "Immunization summary")
    putJsonObject("code") {
      putJsonArray("coding") { add(buildJsonObject { put("code", "folder") }) }
    }
    put("subject", reference("Patient/$patientId"))
    put("date", now.toString())
    putJsonArray("entry") {
      add(buildJsonObject { put("item", reference("DocumentReference/$documentReferenceId")) })
    }
  }

  private fun displayName(patient: JsonObject): String {
    val name = patient["name"]?.jsonArray?.firstOrNull()?.jsonObject
    val given = name?.get("given")?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content }
    return listOfNotNull(given, name?.text("family")).joinToString(" ").ifBlank { patient.id() }
  }
}

private fun newId(): String = UUID.randomUUID().toString()

internal fun JsonObject.id(): String = text("id")!!

internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

private fun String.encodeUrl(): String = URLEncoder.encode(this, Charsets.UTF_8)

private fun reference(value: String): JsonObject = buildJsonObject { put("reference", value) }

private fun coding(system: String, code: String, display: String?): JsonObject = buildJsonObject {
  put("system", system)
  put("code", code)
  display?.let { put("display", it) }
}

private fun codeable(coding: JsonElement): JsonObject = buildJsonObject {
  put("coding", JsonArray(listOf(coding)))
}
