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
package dev.ohs.trustdemo.onesa.feature.patient.list

import androidx.compose.runtime.Composable
import dev.ohs.trustdemo.onesa.data.repository.displayAge
import dev.ohs.trustdemo.onesa.data.repository.personName
import dev.ohs.trustdemo.onesa.generated.config.PatientCardConfig
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.label_age
import dev.ohs.trustdemo.onesa.generated.resources.name_unknown
import dev.ohs.trustdemo.onesa.generated.resources.profile_born
import dev.ohs.trustdemo.onesa.generated.state.PatientSummaryState
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.StatusChip
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource

/** A patient as a list row: the name, the record's reference, sex, birth date and age. */
@Composable
fun PatientCard(
  patient: PatientSummaryState,
  config: PatientCardConfig = PatientCardConfig(),
  onClick: (() -> Unit)? = null,
) {
  val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
  val birthDate = patient.birthDate?.toString()
  RecordRow(
    label =
      personName(patient.givenName, patient.familyName).ifBlank {
        stringResource(Res.string.name_unknown)
      },
    icon = OnesaIcons.User,
    code = patient.mrn ?: patient.patientId?.let { "Patient/$it" },
    detail =
      listOfNotNull(
          if (config.showGender != false) patient.gender?.replaceFirstChar(Char::uppercaseChar)
          else null,
          birthDate?.let { stringResource(Res.string.profile_born, it) },
          if (config.showAge != false)
            displayAge(birthDate, today)?.let { stringResource(Res.string.label_age, it) }
          else null,
        )
        .joinToString(" • ")
        .ifEmpty { null },
    status = patient.active?.takeIf { config.showStatusChip != false }?.let(::activeStatus),
    onClick = onClick,
  )
}

internal fun calculateAge(
  birthDate: String?,
  today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
): String? {
  if (birthDate == null) return null
  return try {
    val birth = LocalDate.parse(birthDate)
    var age = today.year - birth.year
    if (today.month < birth.month || (today.month == birth.month && today.day < birth.day)) {
      age--
    }
    age.toString()
  } catch (_: Exception) {
    null
  }
}

/** Whether the patient record is active, as a chip. */
@Composable
fun ActiveChip(active: Boolean) {
  StatusChip(activeStatus(active))
}

private fun activeStatus(active: Boolean) =
  if (active) Status("Active", Tone.Success) else Status("Inactive", Tone.Danger)
