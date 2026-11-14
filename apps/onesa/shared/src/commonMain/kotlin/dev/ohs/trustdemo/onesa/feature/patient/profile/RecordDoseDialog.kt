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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.data.schedule.DoseProblem
import dev.ohs.trustdemo.onesa.data.verification.EpiDose
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.record_dose
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_again
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_already
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_before_birth
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_cancel
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_date
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_failed
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_future
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_lot
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_lot_missing
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_product
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_save
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_schedule
import dev.ohs.trustdemo.onesa.generated.resources.record_dose_unreadable_date
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RecordDoseDialog(form: RecordDoseForm, viewModel: PatientProfileViewModel) {
  val existing = form.alreadyRecorded
  AlertDialog(
    onDismissRequest = viewModel::closeForm,
    title = { Text(stringResource(Res.string.record_dose)) },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Choice(stringResource(Res.string.record_dose_schedule), form.doses, form.dose) {
          viewModel.pickDose(it)
        }
        if (form.products.isNotEmpty()) {
          Choice(stringResource(Res.string.record_dose_product), form.products, form.product) {
            viewModel.pickProduct(it)
          }
        }
        OutlinedTextField(
          value = form.lotNumber,
          onValueChange = viewModel::typeLot,
          label = { Text(stringResource(Res.string.record_dose_lot)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = form.date,
          onValueChange = viewModel::typeDate,
          label = { Text(stringResource(Res.string.record_dose_date)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        val message =
          when {
            existing != null -> stringResource(Res.string.record_dose_already, existing)
            form.dateUnreadable -> stringResource(Res.string.record_dose_unreadable_date)
            form.problem == DoseProblem.LotNumberMissing ->
              stringResource(Res.string.record_dose_lot_missing)
            form.problem == DoseProblem.DateInTheFuture ->
              stringResource(Res.string.record_dose_future)
            form.problem == DoseProblem.DateBeforeBirth ->
              stringResource(Res.string.record_dose_before_birth)
            form.failed -> stringResource(Res.string.record_dose_failed)
            else -> null
          }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { viewModel.record(confirmed = existing != null) },
        enabled = !form.busy && form.dose != null,
      ) {
        Text(
          stringResource(
            if (existing != null) Res.string.record_dose_again else Res.string.record_dose_save
          )
        )
      }
    },
    dismissButton = {
      TextButton(onClick = viewModel::closeForm) {
        Text(stringResource(Res.string.record_dose_cancel))
      }
    },
  )
}

@Composable
private fun Choice(
  title: String,
  options: List<EpiDose>,
  selected: EpiDose?,
  onPick: (EpiDose) -> Unit,
) {
  Text(title, style = MaterialTheme.typography.labelLarge)
  options.forEach { option ->
    Row(verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected = option == selected, onClick = { onPick(option) })
      Text(option.display)
    }
  }
}
