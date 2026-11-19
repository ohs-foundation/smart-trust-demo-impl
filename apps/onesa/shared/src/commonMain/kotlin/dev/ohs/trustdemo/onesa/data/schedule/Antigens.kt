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

import dev.ohs.trustdemo.onesa.generated.resources.Res
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** A national schedule dose's antigens, and the age in days after which a dose is no longer it. */
@Serializable data class DoseAntigens(val antigens: Set<String>, val latestAgeDays: Int? = null)

/**
 * What each vaccine product and national schedule dose protects against, bundled with the app: a
 * `TrustDemoVaccineProduct` code maps to its antigens as a CVX code maps to CDC's vaccine groups.
 */
@Serializable
class Antigens(
  private val products: Map<String, Set<String>>,
  private val doses: Map<String, DoseAntigens>,
) {
  /**
   * The code of the [schedule] dose that [product], given on [given], counts as: the one with the
   * same antigens due by then, by the PlanDefinition's age, and not outgrown. Null when none is.
   */
  fun doseFor(product: String, given: LocalDate, schedule: Schedule): String? {
    val antigens = products[product] ?: return null
    return schedule.items
      .mapNotNull { item -> item.dueDate?.let { item.dose.code to it } }
      .filter { (code, due) ->
        val dose = doses[code] ?: return@filter false
        val latest = dose.latestAgeDays?.let { schedule.birthDate.plus(it, DateTimeUnit.DAY) }
        dose.antigens == antigens && given >= due && (latest == null || given <= latest)
      }
      .maxByOrNull { (_, due) -> due }
      ?.first
  }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Antigens = json.decodeFromString(text)

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): Antigens =
      parse(Res.readBytes("files/schedule/antigens.json").decodeToString())
  }
}
