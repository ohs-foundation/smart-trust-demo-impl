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
package dev.ohs.trustdemo.control

import dev.ohs.fhir.model.r4.Resource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The story's steps, in order, worded as the build map's journey tables. */
enum class Step(
  val code: String,
  val title: String,
  val who: String,
  val device: String,
  val screens: String,
) {
  J1(
    "J1",
    "Discharge and cards",
    "Dr Anan",
    "Kesaro hospital, facility client",
    "newborn entry, S1, print cards",
  ),
  J2(
    "J2",
    "Household registration",
    "Vilay (CHW)",
    "phone, offline",
    "scan card, S2, registration form, S3",
  ),
  J3A("J3a", "Verify the cards", "Nurse Chanthason", "tablet", "referral inbox, S4"),
  J3B("J3b", "Map and schedule", "Nurse Chanthason", "tablet", "dose match picker, then S5"),
  YEAR(
    "Year",
    "A year of Onesan doses",
    "Nurse Chanthason, or `time move J4.0 --with-year-doses`",
    "tablet",
    "record a dose",
  ),
  J40("J4.0", "Share health records", "Bounmy", "family phone", "S6, share and consent, S7"),
  J41(
    "J4.1",
    "View the summary",
    "Dr Anan",
    "Kesaro clinic, facility client",
    "scan link and passcode, S8",
  ),
}

/** The two servers a step's records are written to: Onesa's HAPI and Taifafeki's Medplum. */
enum class Server {
  HAPI,
  MEDPLUM,
}

/** One FHIR search: the resource type and its parameters. */
data class Query(val type: String, val params: Map<String, String> = emptyMap())

/** The story's dates, which move with the reset day and with the one J3 to J4.0 move. */
data class Dates(val resetDay: LocalDate) {
  private val unmoved = resetDay.minusDays(ChronoUnit.DAYS.between(IG_BIRTH, IG_J3))

  /** Somsak's birth date as the apps enter it: before the move, or after it. */
  val birthDates = setOf(unmoved, unmoved.minusDays(MOVE_DAYS))
}

/**
 * What a step needs written: one search on one [server], and a [satisfied] predicate over the
 * records it returns. [nearMiss] says why a record that was found does not count.
 */
data class Expectation(
  val label: String,
  val server: Server,
  val query: Query,
  val satisfied: (List<Resource>, Dates) -> Boolean,
  val nearMiss: (List<Resource>, Dates) -> String? = { _, _ -> null },
)

internal val IG_BIRTH: LocalDate = LocalDate.parse("2025-10-28")
internal val IG_J3: LocalDate = LocalDate.parse("2025-11-25")
private val IG_J40 = LocalDate.parse("2026-10-26")
private val MOVE_DAYS = ChronoUnit.DAYS.between(IG_J3, IG_J40)

private const val HOSPITAL_NUMBER =
  "http://smart.who.int/trust-demo/sid/xxr-kesaro-hn|CRH-HN-68-104377"
private const val DESIGN = "http://smart.who.int/trust-demo/StructureDefinition"
private const val REGISTRATION =
  "http://smart.who.int/trust-demo/Questionnaire/TrustDemoHouseholdRegistration"
private const val PRODUCT_MAP =
  "http://smart.who.int/trust-demo/ConceptMap/TrustDemoProductToOnesaEpi"
private const val REFERRAL_CODE = "http://snomed.info/sct|3457005"
private const val HEPB_PRODUCT = "vp-hepb-peds-monovalent"
private const val BCG_PRODUCT = "vp-bcg-freeze-dried"
private val ONESAN_DOSES =
  listOf("XXK-EPI-PENTA-1", "XXK-EPI-OPV-1", "XXK-EPI-PCV-1", "XXK-EPI-MR-1")

private val somsakOnOnesa = mapOf("patient.given" to "Somsak")

internal val anchorQuery =
  Query("DocumentReference", mapOf("identifier" to "$IG_ID_SYSTEM|TrustDemoJ1HepB-card"))

private val json = Json

/** `Type/id`, as a record is named in the output. */
fun Resource.ref(): String = "${resourceType()}/$id"

private fun Resource.fields(): JsonObject =
  json.encodeToJsonElement(Resource.serializer(), this).jsonObject

private fun List<Resource>.fields(): List<JsonObject> = map { it.fields() }

private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.flag(name: String): Boolean? = text(name)?.toBooleanStrictOrNull()

private fun JsonElement?.codes(): Set<String> =
  this?.jsonObject
    ?.get("coding")
    ?.jsonArray
    .orEmpty()
    .mapNotNull { it.jsonObject.text("code") }
    .toSet()

private fun JsonObject.codeIn(name: String): Set<String> =
  when (val element = this[name]) {
    is JsonArray -> element.flatMap { it.codes() }.toSet()
    else -> element.codes()
  }

private fun List<JsonObject>.verified() = filter {
  it.text("status") == "completed" && it.flag("primarySource") == true
}

private fun List<JsonObject>.reported() = filter { it.flag("primarySource") == false }

private fun statusMiss(vararg expected: String): (List<Resource>, Dates) -> String? = { found, _ ->
  found
    .fields()
    .firstOrNull { it.text("status") !in expected }
    ?.let { "status ${it.text("status")}, expected ${expected.joinToString(" or ")}" }
}

private fun someWithStatus(vararg expected: String): (List<Resource>, Dates) -> Boolean =
  { found, _ ->
    found.fields().any { it.text("status") in expected }
  }

private fun anyFound(): (List<Resource>, Dates) -> Boolean = { found, _ -> found.isNotEmpty() }

/** What each step needs written, in story order. */
val expectations: Map<Step, List<Expectation>> =
  mapOf(
    Step.J1 to
      listOf(
        Expectation(
          "Somsak's patient record",
          Server.MEDPLUM,
          Query("Patient", mapOf("identifier" to HOSPITAL_NUMBER)),
          anyFound(),
        ),
        Expectation(
          "Birth doses (Hep B, BCG)",
          Server.MEDPLUM,
          Query("Immunization", mapOf("patient.identifier" to HOSPITAL_NUMBER)),
          { found, _ ->
            val products = found.fields().filter { it.text("status") == "completed" }
            listOf(HEPB_PRODUCT, BCG_PRODUCT).all { product ->
              products.any { product in it.codeIn("vaccineCode") }
            }
          },
          statusMiss("completed"),
        ),
      ),
    Step.J2 to
      listOf(
        Expectation(
          "Registration questionnaire response",
          Server.HAPI,
          Query("QuestionnaireResponse", mapOf("questionnaire" to REGISTRATION)),
          someWithStatus("completed", "amended"),
          statusMiss("completed", "amended"),
        ),
        Expectation(
          "Somsak's record on Onesa",
          Server.HAPI,
          Query("Patient", mapOf("family" to "Sengmany", "given" to "Somsak")),
          { found, dates ->
            found.fields().any { it.text("birthDate")?.let(LocalDate::parse) in dates.birthDates }
          },
          { found, dates ->
            found.fields().firstOrNull()?.text("birthDate")?.let { born ->
              "birth date $born, expected ${dates.birthDates.joinToString(" or ")}"
            }
          },
        ),
        Expectation(
          "Mother and father as related persons",
          Server.HAPI,
          Query("RelatedPerson", somsakOnOnesa),
          { found, _ ->
            val kinds = found.fields().flatMap { it.codeIn("relationship") }
            "MTH" in kinds && "FTH" in kinds
          },
        ),
        Expectation(
          "Reported doses",
          Server.HAPI,
          Query("Immunization", somsakOnOnesa),
          { found, _ ->
            val doses = found.fields()
            doses.reported().size >= 2 || doses.verified().size >= 2
          },
        ),
        Expectation(
          "Referral to the health centre",
          Server.HAPI,
          Query("ServiceRequest", somsakOnOnesa + ("code" to REFERRAL_CODE)),
          someWithStatus("active", "completed"),
          statusMiss("active", "completed"),
        ),
      ),
    Step.J3A to
      listOf(
        Expectation(
          "Verified doses",
          Server.HAPI,
          Query("Immunization", somsakOnOnesa),
          { found, _ -> found.fields().verified().size >= 2 },
          { found, _ ->
            found
              .fields()
              .reported()
              .takeIf { it.isNotEmpty() }
              ?.let { "${it.size} still reported" }
          },
        ),
        Expectation(
          "Verification provenance",
          Server.HAPI,
          Query("Provenance", mapOf("_profile" to "$DESIGN/TrustDemoOnesaDvcVerification")),
          anyFound(),
        ),
        Expectation(
          "Reported doses replaced",
          Server.HAPI,
          Query("Immunization", somsakOnOnesa),
          { found, _ ->
            val doses = found.fields()
            doses.verified().isNotEmpty() && doses.reported().isEmpty()
          },
          { found, _ ->
            found
              .fields()
              .reported()
              .takeIf { it.isNotEmpty() }
              ?.let { "${it.size} reported dose(s) left" }
          },
        ),
      ),
    Step.J3B to
      listOf(
        Expectation(
          "Adjudication provenance",
          Server.HAPI,
          Query("Provenance", mapOf("target" to "ConceptMap/TrustDemoProductToOnesaEpi")),
          anyFound(),
        ),
        Expectation(
          "Hep B entry in the concept map",
          Server.HAPI,
          Query("ConceptMap", mapOf("url" to PRODUCT_MAP)),
          { found, _ ->
            found.fields().any { map ->
              map["group"]?.jsonArray.orEmpty().any { group ->
                group.jsonObject["element"]?.jsonArray.orEmpty().any {
                  it.jsonObject.text("code") == HEPB_PRODUCT
                }
              }
            }
          },
        ),
        Expectation(
          "Immunization recommendation",
          Server.HAPI,
          Query("ImmunizationRecommendation", somsakOnOnesa),
          anyFound(),
        ),
        Expectation(
          "Reminder task",
          Server.HAPI,
          Query("Task", somsakOnOnesa),
          { found, _ ->
            found.fields().any {
              it["focus"]
                ?.jsonObject
                ?.text("reference")
                ?.startsWith("ImmunizationRecommendation/") == true
            }
          },
        ),
        Expectation(
          "Referral completed",
          Server.HAPI,
          Query("ServiceRequest", somsakOnOnesa + ("code" to REFERRAL_CODE)),
          someWithStatus("completed"),
          statusMiss("completed"),
        ),
      ),
    Step.YEAR to
      listOf(
        Expectation(
          "Four Onesan doses",
          Server.HAPI,
          Query("Immunization", somsakOnOnesa),
          { found, _ ->
            val coded = found.fields().verified().flatMap { it.codeIn("vaccineCode") }
            ONESAN_DOSES.all { it in coded }
          },
        )
      ),
    Step.J40 to
      listOf(
        Expectation(
          "Active consent",
          Server.HAPI,
          Query(
            "Consent",
            somsakOnOnesa +
              ("_profile" to
                "http://smart.who.int/trust-phw/StructureDefinition/PHWExplicitConsent"),
          ),
          someWithStatus("active"),
          statusMiss("active"),
        ),
        Expectation(
          "Manifest list",
          Server.HAPI,
          Query("List", somsakOnOnesa + ("status" to "current")),
          anyFound(),
        ),
      ),
    Step.J41 to
      listOf(
        Expectation(
          "Access audit event, released",
          Server.HAPI,
          Query(
            "AuditEvent",
            mapOf("_profile" to "$DESIGN/TrustDemoOnesaVhlAccess", "outcome" to "0"),
          ),
          anyFound(),
        ),
        Expectation(
          "Receipt provenance",
          Server.MEDPLUM,
          Query("Provenance", mapOf("_profile" to "$DESIGN/TrustDemoTaifafekiVhlReceipt")),
          anyFound(),
        ),
      ),
  )

/** The records both servers held when asked, by expectation label. */
data class StoryState(
  val today: LocalDate,
  val resetDay: LocalDate,
  val found: Map<String, List<Resource>>,
) {
  val dates = Dates(resetDay)

  fun recordsFor(expectation: Expectation): List<Resource> = found[expectation.label].orEmpty()

  fun isMet(expectation: Expectation): Boolean =
    expectation.satisfied(recordsFor(expectation), dates)

  fun metCount(step: Step): Int = expectations.getValue(step).count(::isMet)

  fun isDone(step: Step): Boolean = metCount(step) == expectations.getValue(step).size

  /** Somsak's birth date as the records hold it, from which the date shift follows. */
  fun shiftDays(): Long? =
    found.values
      .flatten()
      .filter { it.resourceType() == "Patient" }
      .firstNotNullOfOrNull { it.fields().text("birthDate") }
      ?.let { ChronoUnit.DAYS.between(IG_BIRTH, LocalDate.parse(it)) }
}

/**
 * Works out the story's step from the records on both servers, one parallel round of searches per
 * call, with nothing stored between calls.
 */
class StepEngine(
  private val onesa: FhirServer,
  private val kesaro: MedplumAdmin,
  private val clock: Clock = Clock.systemDefaultZone(),
) {
  /**
   * The reset day is when the cards were uploaded, since reset signs them with J3 as that day;
   * before they exist it is today.
   */
  suspend fun state(): StoryState = coroutineScope {
    val today = LocalDate.now(clock)
    val cards = async { kesaro.searchResources(anchorQuery.type, anchorQuery.params) }
    val searches =
      expectations.values.flatten().map { expectation ->
        async { expectation.label to search(expectation) }
      }
    val resetDay =
      cards
        .await()
        .firstNotNullOfOrNull { it.fields()["meta"]?.jsonObject?.text("lastUpdated") }
        ?.let { Instant.parse(it).atZone(clock.zone).toLocalDate() } ?: today
    StoryState(today, resetDay, searches.awaitAll().toMap())
  }

  private suspend fun search(expectation: Expectation): List<Resource> {
    val (type, params) = expectation.query
    val withPage = params + ("_count" to PAGE)
    return when (expectation.server) {
      Server.HAPI -> onesa.search(type, withPage)
      Server.MEDPLUM -> kesaro.searchResources(type, withPage)
    }
  }

  companion object {
    private const val PAGE = "100"

    /** The first step whose expectations are not all met, or null when the story is complete. */
    fun currentStep(state: StoryState): Step? = Step.entries.firstOrNull { !state.isDone(it) }
  }
}
