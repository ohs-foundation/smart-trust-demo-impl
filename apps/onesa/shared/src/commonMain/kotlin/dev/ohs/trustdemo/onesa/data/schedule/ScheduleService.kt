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
package dev.ohs.trustdemo.onesa.data.schedule

import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.PlanDefinition
import dev.ohs.fhir.model.r4.RequestGroup
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.FhirOperator
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.verification.EpiDose
import dev.ohs.trustdemo.onesa.data.verification.ScanTransactions
import dev.ohs.trustdemo.onesa.data.verification.codings
import dev.ohs.trustdemo.onesa.data.verification.text
import dev.ohs.trustdemo.onesa.util.FhirJson
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where one action of the national schedule stands for the child (TD.DT.04). */
sealed interface Standing {
  /** Rule 1: the dose counts, so it is not recommended again. */
  data object Complete : Standing

  /** Rule 2: the dose is for the next visit, recommended from [date]. */
  data class Due(val date: LocalDate) : Standing

  /** Rule 3: the dose belongs to a later visit, from [date]. */
  data class NotYet(val date: LocalDate) : Standing

  /** A condition of this action failed to evaluate; the rest of the schedule is unaffected. */
  data class CouldNotEvaluate(val reason: String) : Standing
}

/** One action of the schedule: the dose, when its age says it falls due, and where it stands. */
data class ScheduleItem(
  val id: String,
  val title: String,
  val dose: EpiDose,
  val age: String,
  val dueDate: LocalDate?,
  val standing: Standing,
  val supportedBy: List<String> = emptyList(),
)

/** A dose of the child's record as the schedule read it. */
data class ScheduleDose(val id: String, val label: String, val codes: List<String> = emptyList())

/**
 * The national schedule evaluated for one child. [counted] doses count (TD.DT.03 rule 3),
 * [reported] doses are shown but not counted (rule 1), [unmapped] verified doses cannot be read by
 * the schedule (rule 2), and [unreadable] doses failed to evaluate.
 */
data class Schedule(
  val patientId: String,
  val birthDate: LocalDate,
  val items: List<ScheduleItem>,
  val counted: List<ScheduleDose>,
  val reported: List<ScheduleDose>,
  val unmapped: List<ScheduleDose>,
  val unreadable: List<ScheduleDose>,
  val visit: LocalDate,
) {
  /** Every action and every dose evaluated, so nothing in the schedule is a guess. */
  val complete: Boolean
    get() = unreadable.isEmpty() && items.none { it.standing is Standing.CouldNotEvaluate }

  val hasDue: Boolean
    get() = items.any { it.standing is Standing.Due }
}

/**
 * Evaluates the national infant schedule with fhir-workflow's `$apply`, in FHIRPath.
 *
 * TD.DT.03 runs per dose, with the Immunization as the subject. TD.DT.04 runs per schedule action,
 * each in a plan of its own, so a condition that does not evaluate (the library fails the whole
 * call on one) fails only that action. What FHIRPath cannot do here stays in Kotlin: `$apply` drops
 * `timingAge`, and weeks and months do not compare in FHIRPath, so the due date and which actions
 * belong to the next visit are worked out here and handed to the conditions as `%nextVisit`.
 */
class ScheduleService(
  private val repository: FhirRepository,
  private val loadPlans: suspend () -> SchedulePlans,
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
  private val json = FhirJson.instance
  private val operator = FhirOperator(NoWorkflowStore)
  private var loaded: SchedulePlans? = null

  suspend fun evaluate(patientId: String): Schedule {
    val plans = loaded ?: loadPlans().also { loaded = it }
    val patient =
      repository.get("Patient", patientId) as? Patient
        ?: error("The child's record is not on this device.")
    val birthDate =
      LocalDate.parse(
        checkNotNull(patient.toJson().text("birthDate")?.takeIf { it.length == 10 }) {
          "The child's date of birth is not known to the day."
        }
      )
    val doses =
      repository.all("Immunization").filter {
        it.toJson().reference("patient") == "Patient/$patientId"
      }
    val verdicts = doses.associateWith { rule(plans, it) }
    val counted = verdicts.filterValues { it == COUNTED }.keys
    val countedCodes = counted.flatMap { it.toJson().scheduleCodes() }

    val planned = plans.infantSchedule.action.map { Planned(it) }
    val readings = planned.associateWith { runCatching { it.read(birthDate) } }
    val sound =
      planned.filter { plan ->
        val reading = readings.getValue(plan).getOrNull() ?: return@filter false
        standing(plans, patient, plan, reading, countedCodes, emptySet()) !is
          Standing.CouldNotEvaluate
      }
    val (visit, nextVisit) =
      nextVisit(sound.map { readings.getValue(it).getOrThrow() }, countedCodes)
    return Schedule(
      patientId = patientId,
      birthDate = birthDate,
      items =
        planned.map { plan ->
          val reading = readings.getValue(plan)
          reading.fold(
            onSuccess = {
              ScheduleItem(
                plan.id,
                plan.title,
                EpiDose(it.code, it.display),
                it.age,
                it.due,
                if (plan in sound) standing(plans, patient, plan, it, countedCodes, nextVisit)
                else Standing.CouldNotEvaluate("A condition could not be evaluated."),
                it.supportedBy,
              )
            },
            onFailure = {
              ScheduleItem(
                plan.id,
                plan.title,
                EpiDose("", ""),
                "",
                null,
                Standing.CouldNotEvaluate(it.message ?: "The action could not be read."),
              )
            },
          )
        },
      counted = counted.map(::scheduleDose),
      reported = verdicts.filterValues { it == REPORTED }.keys.map(::scheduleDose),
      unmapped = verdicts.filterValues { it == UNMAPPED }.keys.map(::scheduleDose),
      unreadable = verdicts.filterValues { it == null }.keys.map(::scheduleDose),
      visit = visit,
    )
  }

  /**
   * The id of the first TD.DT.03 rule that applies to [dose], or null when evaluating it failed.
   */
  private suspend fun rule(plans: SchedulePlans, dose: Resource): String? =
    try {
      apply(plans.doseCounts, dose, emptyMap()).firstOrNull()?.id ?: NO_RULE
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }

  private suspend fun apply(
    plan: PlanDefinition,
    subject: Resource,
    variables: Map<String, Any?>,
  ): List<RequestGroup.Action> =
    operator
      .generateCarePlan(plan, subject, variables, today())
      .contained
      .filterIsInstance<RequestGroup>()
      .single()
      .action

  private suspend fun standing(
    plans: SchedulePlans,
    patient: Patient,
    plan: Planned,
    reading: Reading,
    counted: List<String>,
    nextVisit: Set<String>,
  ): Standing =
    try {
      val isolated = plans.infantSchedule.copy(action = listOf(plan.action))
      val variables =
        mapOf("code" to reading.code, "counted" to codes(counted), "nextVisit" to codes(nextVisit))
      val children = apply(isolated, patient, variables).single().action
      when {
        children.any { it.id == "counted" } -> Standing.Complete
        children.any { it.id == "due" } -> Standing.Due(reading.due)
        else -> Standing.NotYet(reading.due)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Standing.CouldNotEvaluate(e.message ?: "The condition could not be evaluated.")
    }

  /**
   * The codes due at the next visit: today when an uncounted action is already due, so overdue
   * doses are caught up at this visit, otherwise the earliest uncounted age the child has not
   * reached; every uncounted action due by then belongs to it.
   */
  private fun nextVisit(
    readings: List<Reading>,
    counted: List<String>,
  ): Pair<LocalDate, Set<String>> {
    val open = readings.filter { it.code !in counted }
    val visit = open.minOfOrNull { it.due }?.coerceAtLeast(today()) ?: today()
    return visit to open.filter { it.due <= visit }.map { it.code }.toSet()
  }

  /** FHIRPath reads a list handed to it as one value, so the codes travel as a CodeableConcept. */
  private fun codes(codes: Collection<String>) =
    CodeableConcept(coding = codes.map { Coding(code = Code(value = it)) })

  private fun scheduleDose(resource: Resource): ScheduleDose {
    val dose = resource.toJson()
    val codings = dose.codings()
    val product = codings.firstOrNull { it.text("system") == ScanTransactions.PRODUCT_SYSTEM }
    val name =
      product?.text("code")?.let { NewbornRegistration.productName(it) ?: it }
        ?: codings.firstNotNullOfOrNull { it.text("display") ?: it.text("code") }
    val date = dose.text("occurrenceDateTime")?.substringBefore('T')
    return ScheduleDose(
      dose.text("id").orEmpty(),
      listOfNotNull(name, date).joinToString(", "),
      dose.scheduleCodes(),
    )
  }

  private fun JsonObject.scheduleCodes(): List<String> =
    codings()
      .filter { it.text("system") == ScanTransactions.EPI_SYSTEM }
      .mapNotNull { it.text("code") }

  private fun JsonObject.reference(name: String): String? =
    this[name]?.jsonObject?.text("reference")

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject

  private class Reading(
    val code: String,
    val display: String,
    val age: String,
    val due: LocalDate,
    val supportedBy: List<String>,
  )

  /** An action of the schedule; what its code and `timingAge` say is read, and may fail, apart. */
  private inner class Planned(val action: PlanDefinition.Action) {
    private val raw =
      json.encodeToJsonElement(PlanDefinition.Action.serializer(), action).jsonObject
    val id: String = action.id.orEmpty()
    val title: String = raw.text("title").orEmpty()

    fun read(birthDate: LocalDate): Reading {
      val coding =
        raw["code"]
          ?.jsonArray
          ?.firstOrNull()
          ?.jsonObject
          ?.get("coding")
          ?.jsonArray
          ?.firstOrNull()
          ?.jsonObject
      val code = checkNotNull(coding?.text("code")) { "The action has no schedule code." }
      val age = checkNotNull(raw["timingAge"]?.jsonObject) { "The action has no age." }
      return Reading(
        code = code,
        display = coding.text("display") ?: code,
        age = ageLabel(age),
        due = birthDate.plus(age),
        supportedBy =
          raw["extension"]
            ?.jsonArray
            .orEmpty()
            .map { it.jsonObject }
            .filter { it.text("url") == SUPPORTED_BY }
            .mapNotNull { it.text("valueCode") },
      )
    }
  }

  private fun ageLabel(age: JsonObject): String {
    val value = age.getValue("value").jsonPrimitive.doubleOrNull?.toInt() ?: error("no age")
    return if (value == 0) "Birth" else "$value-${age.text("unit").orEmpty().removeSuffix("s")}"
  }

  private fun LocalDate.plus(age: JsonElement): LocalDate {
    val value =
      age.jsonObject.getValue("value").jsonPrimitive.doubleOrNull?.toInt() ?: error("no age")
    return when (val unit = age.jsonObject.getValue("code").jsonPrimitive.contentOrNull) {
      "d" -> plus(value, DateTimeUnit.DAY)
      "wk" -> plus(value, DateTimeUnit.WEEK)
      "mo" -> plus(value, DateTimeUnit.MONTH)
      else -> error("Age unit $unit is not supported.")
    }
  }

  private companion object {
    const val REPORTED = "rule-1"
    const val UNMAPPED = "rule-2"
    const val COUNTED = "rule-3"
    const val NO_RULE = "none"
    const val SUPPORTED_BY = "http://ohs.dev/fhir/trust-demo/StructureDefinition/supported-by"
  }
}
