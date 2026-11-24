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
import dev.ohs.trustdemo.trustkit.CardSigner
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64
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

/** The apps and devices the story is driven on, each drawn as one box of the journey map. */
enum class App(val label: String, val driver: String, val description: String) {
  EMR("Hospital EMR", "Dr Anan", "Taifafeki facility client, hospital EMR"),
  CHW_PHONE("CHW phone", "Vilay (offline)", "Onesa app, CHW, phone offline"),
  CLINIC_TABLET("Clinic tablet", "Nurse Chanthason", "Onesa app, nurse, tablet"),
  FAMILY_WALLET("Family wallet", "Bounmy", "Onesa app, family phone"),
  VHL_VIEWER("VHL viewer", "Dr Anan", "Taifafeki facility client, clinic viewer"),
}

/** The story's steps, in order, titled as the IG's user scenarios. */
enum class Step(
  val code: String,
  val title: String,
  val who: String,
  val device: String,
  val screens: String,
  val app: App,
  val next: String,
  val qr: String? = null,
) {
  J1(
    "J1",
    "Vaccinated and recorded",
    "Dr Anan",
    "Kesaro hospital, facility client",
    "newborn entry, S1, print cards",
    App.EMR,
    "enter Somsak's discharge and print his two cards",
  ),
  J2(
    "J2",
    "Found and registered",
    "Vilay (CHW)",
    "phone, offline",
    "scan card, S2, registration form, S3",
    App.CHW_PHONE,
    "scan a card, register the household and refer Somsak",
    "somsak-hepb, somsak-bcg",
  ),
  J3A(
    "J3a",
    "A foreign record used for the next vaccine",
    "Nurse Chanthason",
    "tablet",
    "referral inbox, S4",
    App.CLINIC_TABLET,
    "open the referral and verify the cards",
    "somsak-hepb, somsak-bcg",
  ),
  J3B(
    "J3b",
    "A foreign record used for the next vaccine",
    "Nurse Chanthason",
    "tablet",
    "dose match picker, then S5",
    App.CLINIC_TABLET,
    "match the doses to the Onesa schedule and book the next visit",
  ),
  YEAR(
    "Year",
    "A year of doses in Onesa",
    "Nurse Chanthason, or `time move J4.0 --with-year-doses`",
    "tablet",
    "record a dose",
    App.CLINIC_TABLET,
    "record a dose, or run `trust-demo time move J4.0 --with-year-doses`",
  ),
  J40(
    "J4.0",
    "Continuity across a border: one family, one phone",
    "Bounmy",
    "family phone",
    "S6, share and consent, S7",
    App.FAMILY_WALLET,
    "share Somsak's records and consent",
  ),
  J41(
    "J4.1",
    "Continuity across a border: a paediatrician in Taifafeki sees the full history",
    "Dr Anan",
    "Kesaro paediatric clinic, facility client",
    "scan link and passcode, S8",
    App.VHL_VIEWER,
    "scan the link, enter the passcode and view the summary",
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
data class Dates(val resetDay: LocalDate, val moved: Boolean = false) {
  private val unmoved = resetDay.minusDays(ChronoUnit.DAYS.between(IG_BIRTH, IG_J3))

  /** Somsak's birth date as the apps enter it, less the move's days once the move is made. */
  val birthDate: LocalDate = if (moved) unmoved.minusDays(MOVE_DAYS) else unmoved
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
internal val IG_J3: LocalDate = LocalDate.parse("2026-01-06")
internal val IG_J40: LocalDate = LocalDate.parse("2026-10-26")
internal val MOVE_DAYS = ChronoUnit.DAYS.between(IG_J3, IG_J40)
internal const val HOSPITAL_NUMBER_SYSTEM = "http://smart.who.int/trust-demo/sid/xxr-kesaro-hn"
private const val SOMSAK_HOSPITAL_NUMBER = "CRH-HN-68-104377"
internal const val HOSPITAL_NUMBER = "$HOSPITAL_NUMBER_SYSTEM|$SOMSAK_HOSPITAL_NUMBER"
private const val NOT_REGISTERED = "not-registered-on-onesa"
private const val DESIGN = "http://smart.who.int/trust-demo/StructureDefinition"
private const val REGISTRATION =
  "http://ohs.dev/fhir/trust-demo/Questionnaire/TrustDemoHouseholdRegistration"
private const val PRODUCT_MAP =
  "http://smart.who.int/trust-demo/ConceptMap/TrustDemoProductToOnesaEpi"
private const val REFERRAL_CODE = "http://snomed.info/sct|3457005"
private const val HEPB_PRODUCT = "a3K3X000006MiiXUAS"
private const val BCG_PRODUCT = "a3K3X000006MiisUAC"
private val ONESA_DOSES =
  listOf("XXU-EPI-PENTA-1", "XXU-EPI-OPV-1", "XXU-EPI-PCV-1", "XXU-EPI-MR-1")

/**
 * The child whose story is followed. [onesaIds] are the child's Patient ids on Onesa, found through
 * the cards' fingerprints once the CHW registers them; null follows the IG's Somsak by name.
 */
data class Newborn(val hospitalNumber: String, val onesaIds: List<String>? = null) {
  val isSomsak: Boolean = onesaIds == null

  val who: String = if (isSomsak) "Somsak" else "The newborn"

  val identifier: String = "$HOSPITAL_NUMBER_SYSTEM|$hospitalNumber"

  val anchor: Query = Query("DocumentReference", mapOf("identifier" to identifier))

  internal val onesaOwn: Map<String, String> =
    onesaIds?.let { mapOf("patient" to it.ids()) } ?: mapOf("patient.given" to "Somsak")

  internal val onesaPatient: Map<String, String> =
    onesaIds?.let { mapOf("_id" to it.ids()) } ?: mapOf("family" to "Sengmany", "given" to "Somsak")

  /** What narrows a search of records that are not filed under a patient, to this child's. */
  internal fun narrowed(param: String, forEach: (String) -> String = { it }): Map<String, String> =
    onesaIds?.let { ids -> mapOf(param to ids.ids(forEach)) }.orEmpty()

  private fun List<String>.ids(forEach: (String) -> String = { it }) =
    ifEmpty { listOf(NOT_REGISTERED) }.joinToString(",", transform = forEach)

  companion object {
    val SOMSAK = Newborn(SOMSAK_HOSPITAL_NUMBER)
  }
}

internal fun fingerprint(hc1: String): String =
  MessageDigest.getInstance("SHA-256").digest(hc1.trim().toByteArray()).joinToString("") {
    "%02x".format(it)
  }

internal const val FINGERPRINT_SYSTEM = "http://ohs.dev/trust-demo/identifier/hcert-sha256"

private val json = Json

/** `Type/id`, as a record is named in the output. */
fun Resource.ref(): String = "${resourceType()}/$id"

internal fun Resource.fields(): JsonObject =
  json.encodeToJsonElement(Resource.serializer(), this).jsonObject

private fun List<Resource>.fields(): List<JsonObject> = map { it.fields() }

internal fun Resource.cardAttachment(): JsonObject? =
  runCatching { fields()["content"]!!.jsonArray[0].jsonObject["attachment"]!!.jsonObject }
    .getOrNull()

internal fun Resource.cardText(): String? =
  cardAttachment()?.text("data")?.let { String(Base64.getDecoder().decode(it)) }

internal fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

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

private fun List<JsonObject>.coverBirthDoses() =
  listOf(HEPB_PRODUCT, BCG_PRODUCT).all { product -> any { product in it.codeIn("vaccineCode") } }

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

/** What each step needs written for the IG's Somsak, in story order. */
val expectations: Map<Step, List<Expectation>> = expectationsFor(Newborn.SOMSAK)

/** What each step needs written for [newborn], in story order. */
fun expectationsFor(newborn: Newborn): Map<Step, List<Expectation>> {
  val onesaOwn = newborn.onesaOwn
  val sOnOnesa = "${newborn.who}'s record on Onesa"
  val sOnKesaro = "${newborn.who}'s patient record"
  val hospitalNumber = newborn.identifier
  return mapOf(
    Step.J1 to
      listOf(
        Expectation(
          sOnKesaro,
          Server.MEDPLUM,
          Query("Patient", mapOf("identifier" to hospitalNumber)),
          anyFound(),
        ),
        Expectation(
          "Birth doses (Hep B, BCG)",
          Server.MEDPLUM,
          Query("Immunization", mapOf("patient.identifier" to hospitalNumber)),
          { found, _ ->
            found.fields().filter { it.text("status") == "completed" }.coverBirthDoses()
          },
          statusMiss("completed"),
        ),
      ),
    Step.J2 to
      listOf(
        Expectation(
          "Registration questionnaire response",
          Server.HAPI,
          Query(
            "QuestionnaireResponse",
            mapOf("questionnaire" to REGISTRATION) + newborn.narrowed("subject") { "Patient/$it" },
          ),
          someWithStatus("completed", "amended"),
          statusMiss("completed", "amended"),
        ),
        Expectation(
          sOnOnesa,
          Server.HAPI,
          Query("Patient", newborn.onesaPatient),
          { found, dates ->
            if (newborn.isSomsak)
              found.fields().any { it.text("birthDate")?.let(LocalDate::parse) == dates.birthDate }
            else found.isNotEmpty()
          },
          { found, dates ->
            found
              .fields()
              .firstOrNull()
              ?.text("birthDate")
              ?.takeIf { newborn.isSomsak }
              ?.let { "birth date $it, expected ${dates.birthDate}" }
          },
        ),
        Expectation(
          "Mother and father as related persons",
          Server.HAPI,
          Query("RelatedPerson", onesaOwn),
          { found, _ ->
            val kinds = found.fields().flatMap { it.codeIn("relationship") }
            "MTH" in kinds && "FTH" in kinds
          },
        ),
        Expectation(
          "Reported doses",
          Server.HAPI,
          Query("Immunization", onesaOwn),
          { found, _ ->
            val doses = found.fields()
            doses.reported().coverBirthDoses() || doses.verified().coverBirthDoses()
          },
        ),
        Expectation(
          "Referral to the health centre",
          Server.HAPI,
          Query("ServiceRequest", onesaOwn + ("code" to REFERRAL_CODE)),
          someWithStatus("active", "completed"),
          statusMiss("active", "completed"),
        ),
      ),
    Step.J3A to
      listOf(
        Expectation(
          "Verified doses",
          Server.HAPI,
          Query("Immunization", onesaOwn),
          { found, _ -> found.fields().verified().coverBirthDoses() },
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
          Query(
            "Provenance",
            mapOf("_profile" to "$DESIGN/TrustDemoOnesaDvcVerification") +
              newborn.narrowed("patient"),
          ),
          anyFound(),
        ),
        Expectation(
          "Reported doses replaced",
          Server.HAPI,
          Query("Immunization", onesaOwn),
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
          Query("ImmunizationRecommendation", onesaOwn),
          anyFound(),
        ),
        Expectation(
          "Reminder task",
          Server.HAPI,
          Query("Task", onesaOwn),
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
          Query("ServiceRequest", onesaOwn + ("code" to REFERRAL_CODE)),
          someWithStatus("completed"),
          statusMiss("completed"),
        ),
      ),
    Step.YEAR to
      listOf(
        Expectation(
          "Four doses given in Onesa",
          Server.HAPI,
          Query("Immunization", onesaOwn),
          { found, _ ->
            val coded = found.fields().verified().flatMap { it.codeIn("vaccineCode") }
            ONESA_DOSES.all { it in coded }
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
            onesaOwn +
              ("_profile" to
                "http://smart.who.int/trust-phw/StructureDefinition/PHWExplicitConsent"),
          ),
          someWithStatus("active"),
          statusMiss("active"),
        ),
        Expectation(
          "Manifest list",
          Server.HAPI,
          Query("List", onesaOwn + ("status" to "current")),
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
            mapOf("_profile" to "$DESIGN/TrustDemoOnesaVhlAccess", "outcome" to "0") +
              newborn.narrowed("patient"),
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
}

/** The records both servers held when asked, by expectation label. */
data class StoryState(
  val today: LocalDate,
  val resetDay: LocalDate,
  val found: Map<String, List<Resource>>,
  val newborn: Newborn = Newborn.SOMSAK,
) {
  val expectations: Map<Step, List<Expectation>> = expectationsFor(newborn)

  /** Whether the J3 to J4.0 move is made on Onesa, read from Somsak's birth date there. */
  val movedOnOnesa: Boolean = newborn.isSomsak && bornAfterMove("Somsak's record on Onesa")

  /** Whether the move is made on Kesaro, read from Somsak's birth date there. */
  val movedOnKesaro: Boolean = newborn.isSomsak && bornAfterMove("Somsak's patient record")

  val dates = Dates(resetDay, movedOnOnesa)

  fun recordsFor(expectation: Expectation): List<Resource> = found[expectation.label].orEmpty()

  fun isMet(expectation: Expectation): Boolean =
    expectation.satisfied(recordsFor(expectation), dates)

  fun metCount(step: Step): Int = expectations.getValue(step).count(::isMet)

  fun isDone(step: Step): Boolean = metCount(step) == expectations.getValue(step).size

  private fun bornAfterMove(label: String): Boolean =
    found[label].orEmpty().fields().any {
      it.text("birthDate")?.let(LocalDate::parse) == Dates(resetDay).birthDate.minusDays(MOVE_DAYS)
    }

  /** How far the reset day lies from the IG's J3. */
  fun shiftDays(): Long = ChronoUnit.DAYS.between(IG_J3, resetDay)
}

/** Something that can say where the story stands. */
fun interface StoryStates {
  suspend fun state(): StoryState
}

/**
 * Works out the story's step from the records on both servers, one parallel round of searches per
 * call, with nothing stored between calls.
 */
class StepEngine(
  private val onesa: FhirServer,
  private val kesaro: MedplumAdmin,
  private val clock: Clock = Clock.systemDefaultZone(),
  private val hospitalNumber: String = Newborn.SOMSAK.hospitalNumber,
) : StoryStates {
  /**
   * The reset day is the IG's J3 moved by the shift in Somsak's earliest issued card's vaccination
   * date; before J1 issues cards it is today. Any other newborn is found on Onesa through the
   * fingerprints of the cards issued for their hospital number.
   */
  override suspend fun state(): StoryState = coroutineScope {
    val today = LocalDate.now(clock)
    val anchor = Newborn(hospitalNumber).anchor
    val cards = kesaro.searchResources(anchor.type, anchor.params)
    val newborn =
      if (hospitalNumber == Newborn.SOMSAK.hospitalNumber) Newborn.SOMSAK
      else Newborn(hospitalNumber, onesaIds(cards))
    val searches =
      expectationsFor(newborn)
        .values
        .flatten()
        .groupBy { it.server to it.query }
        .map { (where, group) ->
          async { group.map { it.label } to search(where.first, where.second) }
        }
    val found = searches.awaitAll().flatMap { (labels, records) -> labels.map { it to records } }
    val resetDay = if (newborn.isSomsak) resetDayFrom(cards) else null
    StoryState(today, resetDay ?: today, found.toMap(), newborn)
  }

  private suspend fun onesaIds(cards: List<Resource>): List<String> {
    val fingerprints = cards.mapNotNull { it.cardText() }.map(::fingerprint)
    if (fingerprints.isEmpty()) return emptyList()
    val identifier = fingerprints.joinToString(",") { "$FINGERPRINT_SYSTEM|$it" }
    return onesa
      .search("Immunization", mapOf("identifier" to identifier, "_count" to PAGE))
      .mapNotNull { it.fields()["patient"]?.jsonObject?.text("reference") }
      .map { it.removePrefix("Patient/") }
      .distinct()
  }

  private suspend fun search(server: Server, query: Query): List<Resource> {
    val params = query.params + ("_count" to PAGE)
    return when (server) {
      Server.HAPI -> onesa.search(query.type, params)
      Server.MEDPLUM -> kesaro.searchResources(query.type, params)
    }
  }

  private fun resetDayFrom(cards: List<Resource>): LocalDate? =
    cards
      .mapNotNull { card ->
        runCatching { CardSigner.vaccinationDate(card.cardText()!!) }.getOrNull()
      }
      .minOrNull()
      ?.let { IG_J3.plusDays(ChronoUnit.DAYS.between(IG_BIRTH, it)) }

  companion object {
    private const val PAGE = "100"

    /** The first step whose expectations are not all met, or null when the story is complete. */
    fun currentStep(state: StoryState): Step? = Step.entries.firstOrNull { !state.isDone(it) }
  }
}
