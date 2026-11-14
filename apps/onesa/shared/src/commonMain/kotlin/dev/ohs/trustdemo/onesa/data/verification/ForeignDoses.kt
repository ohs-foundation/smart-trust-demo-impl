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
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.repository.personName
import dev.ohs.trustdemo.onesa.util.FhirJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Who confirmed a product's schedule dose (DT.02 rule 3), and when. */
data class Adjudication(val attester: String, val recorded: String?)

/**
 * A verified dose imported from a foreign card: the product as issued, the schedule dose it carries
 * ([schedule]), whether the national map names the product ([mapped]), whether a verification
 * Provenance is attached ([verified]) and the nurse's [adjudication], if she made one.
 */
data class ForeignDose(
  val id: String,
  val product: String,
  val productName: String,
  val date: String?,
  val lot: String?,
  val origin: String?,
  val schedule: EpiDose?,
  val mapped: Boolean,
  val verified: Boolean,
  val adjudication: Adjudication? = null,
)

/** Reads a child's verified foreign doses, and their Provenance, from the synced copy. */
class ForeignDoses(private val repository: FhirRepository) {
  private val json = FhirJson.instance

  suspend fun of(patientId: String): List<ForeignDose> {
    val mapped = NationalMap(repository).epiCodes()
    val names = scheduleNames()
    val provenances = records("Provenance")
    return records("Immunization")
      .filter {
        it["patient"]?.jsonObject?.text("reference") == "Patient/$patientId" &&
          it.text("primarySource") == "true"
      }
      .mapNotNull { dose ->
        val id = dose.text("id") ?: return@mapNotNull null
        val codings = dose.codings()
        val product =
          codings.firstOrNull { it.text("system") == ScanTransactions.PRODUCT_SYSTEM }
            ?: return@mapNotNull null
        val code = product.text("code") ?: return@mapNotNull null
        val schedule =
          codings
            .firstOrNull { it.text("system") == ScanTransactions.EPI_SYSTEM }
            ?.text("code")
            ?.let { EpiDose(it, names[it] ?: it) }
        val about = provenances.filter { "Immunization/$id" in it.targets() }
        ForeignDose(
          id = id,
          product = code,
          productName = NewbornRegistration.PRODUCTS[code] ?: product.text("display") ?: code,
          date = dose.text("occurrenceDateTime")?.substringBefore('T'),
          lot = dose.text("lotNumber"),
          origin = dose["location"]?.jsonObject?.text("display"),
          schedule = schedule,
          mapped = code in mapped,
          verified = about.any { it.isVerification() },
          adjudication =
            about.firstNotNullOfOrNull { provenance ->
              provenance.agent(ATTESTER)?.let {
                Adjudication(practitionerName(it), provenance.text("recorded"))
              }
            },
        )
      }
      .sortedBy { it.date }
  }

  private suspend fun scheduleNames(): Map<String, String> =
    records("CodeSystem")
      .filter { it.text("url") == ScanTransactions.EPI_SYSTEM }
      .flatMap { it["concept"]?.jsonArray.orEmpty() }
      .mapNotNull { concept ->
        val code = concept.jsonObject.text("code") ?: return@mapNotNull null
        concept.jsonObject.text("display")?.let { code to it }
      }
      .toMap()

  private suspend fun practitionerName(reference: String): String {
    val name =
      repository
        .get("Practitioner", reference.removePrefix("Practitioner/"))
        ?.toJson()
        ?.get("name")
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
    return name?.text("text")
      ?: name?.let {
        personName(it["given"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content, it.text("family"))
      }
      ?: reference
  }

  private fun JsonObject.targets(): List<String> =
    this["target"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.text("reference") }

  private fun JsonObject.isVerification(): Boolean =
    this["meta"]?.jsonObject?.get("profile")?.jsonArray.orEmpty().any {
      it.jsonPrimitive.content.endsWith("/$VERIFICATION_PROFILE")
    }

  private fun JsonObject.agent(type: String): String? =
    this["agent"]
      ?.jsonArray
      .orEmpty()
      .map { it.jsonObject }
      .firstOrNull { agent ->
        agent["type"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().any {
          it.jsonObject.text("code") == type
        }
      }
      ?.get("who")
      ?.jsonObject
      ?.text("reference")

  private suspend fun records(type: String): List<JsonObject> =
    repository.all(type).map { it.toJson() }

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject

  private companion object {
    const val ATTESTER = "attester"
    const val VERIFICATION_PROFILE = "TrustDemoOnesaDvcVerification"
  }
}

/**
 * A schedule dose's display as a short label: parentheses dropped and `, dose N` as `N`, so
 * `Pentavalent (DTP-HepB-Hib), dose 1` reads `Pentavalent 1`.
 */
fun shortDoseName(display: String): String =
  display
    .replace(Regex("""\s*\([^)]*\)"""), "")
    .replace(Regex(""",\s*dose\s+(\d+)$"""), " $1")
    .trim()

val EpiDose.shortName: String
  get() = shortDoseName(display)
