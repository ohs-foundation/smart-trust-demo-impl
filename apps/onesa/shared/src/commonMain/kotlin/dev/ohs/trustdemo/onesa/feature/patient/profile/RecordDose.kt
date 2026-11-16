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
package dev.ohs.trustdemo.onesa.feature.patient.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.data.schedule.DoseProblem
import dev.ohs.trustdemo.onesa.data.verification.EpiDose
import dev.ohs.trustdemo.onesa.data.verification.shortDoseName
import dev.ohs.trustdemo.onesa.data.verification.shortName
import dev.ohs.trustdemo.onesa.feature.referral.ChildTopBar
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.child_household_id
import dev.ohs.trustdemo.onesa.generated.resources.patient_profile_default_name
import dev.ohs.trustdemo.onesa.generated.resources.record_also_due
import dev.ohs.trustdemo.onesa.generated.resources.record_caption
import dev.ohs.trustdemo.onesa.generated.resources.record_choose
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_again
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_already
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_before_birth
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_date
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_failed
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_future
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_lot
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_lot_missing
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_product
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_save
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_schedule
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_unreadable_date
import dev.ohs.trustdemo.onesa.generated.resources.record_due
import dev.ohs.trustdemo.onesa.generated.resources.record_given_by
import dev.ohs.trustdemo.onesa.generated.resources.record_nothing_else
import dev.ohs.trustdemo.onesa.generated.resources.record_reminder_due
import dev.ohs.trustdemo.onesa.generated.resources.record_schedule_only
import dev.ohs.trustdemo.onesa.generated.resources.record_title
import dev.ohs.trustdemo.onesa.generated.resources.record_visit
import dev.ohs.trustdemo.onesa.generated.resources.sync_first
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaSelectField
import dev.ohs.trustdemo.onesa.ui.OnesaTextField
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.displayDate
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * 1-16: the nurse records a dose she gave today. The doses the booked visit also recommends sit
 * beside the form, and the bar says which Immunization the save writes.
 */
@Composable
fun RecordDoseContent(
  form: RecordDoseForm,
  state: ProfileUiState,
  givenBy: String?,
  today: LocalDate,
  onBack: () -> Unit,
  onPickDose: (EpiDose) -> Unit,
  onPickProduct: (EpiDose) -> Unit,
  onLot: (String) -> Unit,
  onDate: (String) -> Unit,
  onSave: (confirmed: Boolean) -> Unit,
) {
  val visit = state.visit
  val again = form.alreadyRecorded
  val given = state.immunizations.mapNotNull { it.scheduleCode }.toSet()
  val alsoDue = visit?.doses.orEmpty().filter { it.code != form.dose?.code && it.code !in given }
  val reminderDue =
    visit?.reminderId?.takeIf { visit.reminderFrom?.let { it <= today.toString() } == true }
  val household =
    state.context?.householdId?.let { stringResource(Res.string.child_household_id, it) }
  OnesaScaffold(
    topBar = {
      ChildTopBar(
        child = state.context,
        fallbackName = stringResource(Res.string.patient_profile_default_name),
        today = today,
        onBack = onBack,
        subtitle =
          listOfNotNull(household, stringResource(Res.string.record_visit, displayDate(today)))
            .joinToString(" • "),
        chips =
          listOfNotNull(
            reminderDue?.let {
              Status(stringResource(Res.string.record_reminder_due, it), Tone.Warning)
            }
          ),
      )
    },
    side = {
      SectionCard(title = stringResource(Res.string.record_also_due)) {
        if (alsoDue.isEmpty()) {
          Text(
            stringResource(Res.string.record_nothing_else),
            style = MaterialTheme.typography.bodyMedium,
            color = OnesaColors.Muted,
          )
        }
        alsoDue.forEach { dose ->
          RecordRow(
            label = shortDoseName(dose.name),
            code = dose.code,
            status = Status(stringResource(Res.string.record_due), Tone.Warning),
          )
        }
      }
    },
    actions = {
      PrimaryActionBar(
        listOf(
          Action(
            stringResource(
              if (again != null) Res.string.record_dose_again else Res.string.record_dose_save
            ),
            onClick = { onSave(again != null) },
            busy = form.busy,
            enabled = !form.busy && form.dose != null,
          )
        ),
        caption = stringResource(Res.string.record_caption, form.recordingId),
      )
    },
  ) {
    SectionCard(title = stringResource(Res.string.record_title)) {
      problem(form)?.let { AlertCard(null, it, if (again != null) Tone.Warning else Tone.Danger) }
      FieldPair({
        OnesaSelectField(
          label = stringResource(Res.string.record_dose_schedule),
          value = form.dose?.let(::doseLabel) ?: stringResource(Res.string.record_choose),
          options = form.doses.map(::doseLabel),
          onSelect = { onPickDose(form.doses[it]) },
          modifier = it,
        )
      }) {
        if (form.products.isEmpty()) {
          OnesaTextField(
            label = stringResource(Res.string.record_dose_product),
            value = stringResource(Res.string.record_schedule_only),
            onValueChange = {},
            readOnly = true,
            modifier = it,
          )
        } else {
          OnesaSelectField(
            label = stringResource(Res.string.record_dose_product),
            value = form.product?.code ?: stringResource(Res.string.record_choose),
            options = form.products.map { product -> product.code },
            onSelect = { index -> onPickProduct(form.products[index]) },
            modifier = it,
            mono = true,
          )
        }
      }
      FieldPair({
        OnesaTextField(
          label = stringResource(Res.string.record_dose_lot),
          value = form.lotNumber,
          onValueChange = onLot,
          mono = true,
          modifier = it,
        )
      }) {
        OnesaTextField(
          label = stringResource(Res.string.record_dose_date),
          value = form.date,
          onValueChange = onDate,
          placeholder = "yyyy-mm-dd",
          modifier = it,
        )
      }
      FieldPair({
        OnesaTextField(
          label = stringResource(Res.string.record_given_by),
          value = givenBy.orEmpty(),
          onValueChange = {},
          readOnly = true,
          modifier = it,
        )
      }) {
        Spacer(it)
      }
    }
  }
}

/** Two fields side by side, as the form's two columns. */
@Composable
private fun FieldPair(
  first: @Composable (Modifier) -> Unit,
  second: @Composable (Modifier) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    first(Modifier.weight(1f))
    second(Modifier.weight(1f))
  }
}

private fun doseLabel(dose: EpiDose): String = "${dose.shortName} • ${dose.code}"

@Composable
private fun problem(form: RecordDoseForm): String? =
  when {
    form.alreadyRecorded != null ->
      stringResource(Res.string.record_dose_already, form.alreadyRecorded.toString())
    form.dateUnreadable -> stringResource(Res.string.record_dose_unreadable_date)
    form.problem == DoseProblem.LotNumberMissing ->
      stringResource(Res.string.record_dose_lot_missing)
    form.problem == DoseProblem.DateInTheFuture -> stringResource(Res.string.record_dose_future)
    form.problem == DoseProblem.DateBeforeBirth ->
      stringResource(Res.string.record_dose_before_birth)
    form.syncFirst -> stringResource(Res.string.sync_first)
    form.failed -> stringResource(Res.string.record_dose_failed)
    else -> null
  }
