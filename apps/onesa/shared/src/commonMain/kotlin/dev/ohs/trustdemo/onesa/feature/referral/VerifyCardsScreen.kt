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
package dev.ohs.trustdemo.onesa.feature.referral

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.data.hcert.ListFreshness
import dev.ohs.trustdemo.onesa.data.verification.EpiDose
import dev.ohs.trustdemo.onesa.data.verification.ScanOutcome
import dev.ohs.trustdemo.onesa.data.verification.UnmatchedDose
import dev.ohs.trustdemo.onesa.data.verification.display
import dev.ohs.trustdemo.onesa.feature.scan.CardCameraPanel
import dev.ohs.trustdemo.onesa.feature.scan.QrScan
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.list_stale
import dev.ohs.trustdemo.onesa.generated.resources.match_changed
import dev.ohs.trustdemo.onesa.generated.resources.match_confirm
import dev.ohs.trustdemo.onesa.generated.resources.match_done
import dev.ohs.trustdemo.onesa.generated.resources.match_hint
import dev.ohs.trustdemo.onesa.generated.resources.match_none
import dev.ohs.trustdemo.onesa.generated.resources.match_title
import dev.ohs.trustdemo.onesa.generated.resources.scan_back
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_camera
import dev.ohs.trustdemo.onesa.generated.resources.scan_paste_label
import dev.ohs.trustdemo.onesa.generated.resources.verify_already_imported
import dev.ohs.trustdemo.onesa.generated.resources.verify_birth_dates
import dev.ohs.trustdemo.onesa.generated.resources.verify_confirm
import dev.ohs.trustdemo.onesa.generated.resources.verify_confirm_card
import dev.ohs.trustdemo.onesa.generated.resources.verify_confirm_child
import dev.ohs.trustdemo.onesa.generated.resources.verify_confirm_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_could_not_save
import dev.ohs.trustdemo.onesa.generated.resources.verify_decline
import dev.ohs.trustdemo.onesa.generated.resources.verify_failed
import dev.ohs.trustdemo.onesa.generated.resources.verify_failed_reason
import dev.ohs.trustdemo.onesa.generated.resources.verify_imported
import dev.ohs.trustdemo.onesa.generated.resources.verify_imported_elsewhere
import dev.ohs.trustdemo.onesa.generated.resources.verify_list_fetched
import dev.ohs.trustdemo.onesa.generated.resources.verify_list_stale
import dev.ohs.trustdemo.onesa.generated.resources.verify_nothing_imported
import dev.ohs.trustdemo.onesa.generated.resources.verify_open_schedule
import dev.ohs.trustdemo.onesa.generated.resources.verify_other_child
import dev.ohs.trustdemo.onesa.generated.resources.verify_replaced
import dev.ohs.trustdemo.onesa.generated.resources.verify_results
import dev.ohs.trustdemo.onesa.generated.resources.verify_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_use_text
import dev.ohs.trustdemo.onesa.ui.OnesaTextField
import dev.ohs.trustdemo.onesa.util.displayTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** S4: verifies each of a referred child's cards against the trust list and imports its dose. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyCardsScreen(referralId: String, onBack: () -> Unit, onOpenSchedule: () -> Unit) {
  val viewModel = koinViewModel<VerifyCardsViewModel>(key = referralId) { parametersOf(referralId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var pasted by remember { mutableStateOf("") }
  var cameraFailed by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            state.referral?.let { "${stringResource(Res.string.verify_title)}: ${it.patientName}" }
              ?: stringResource(Res.string.verify_title)
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(Res.string.scan_back),
              tint = MaterialTheme.colorScheme.onPrimary,
            )
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
          ),
      )
    }
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      val onScan = { scan: QrScan ->
        when (scan) {
          is QrScan.Text -> viewModel.onText(scan.value)
          is QrScan.Failed -> cameraFailed = true
          QrScan.Cancelled -> Unit
        }
      }
      CardCameraPanel(onResult = onScan)
      if (cameraFailed) {
        Text(stringResource(Res.string.scan_error_camera), color = MaterialTheme.colorScheme.error)
      }
      OnesaTextField(
        label = stringResource(Res.string.scan_paste_label),
        value = pasted,
        onValueChange = { pasted = it },
        placeholder = "HC1:…",
        mono = true,
        minLines = 2,
      )
      OutlinedButton(
        onClick = {
          viewModel.onText(pasted)
          pasted = ""
        },
        enabled = pasted.isNotBlank() && !state.busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(Res.string.verify_use_text))
      }
      if (state.busy) CircularProgressIndicator()
      if (state.couldNotSave) {
        Text(
          stringResource(Res.string.verify_could_not_save),
          color = MaterialTheme.colorScheme.error,
        )
      }

      state.pending?.let { pending ->
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              stringResource(Res.string.verify_confirm_title),
              style = MaterialTheme.typography.labelLarge,
            )
            Text(
              stringResource(
                Res.string.verify_confirm_card,
                pending.holderName,
                pending.holderBirthDate,
              )
            )
            Text(stringResource(Res.string.verify_confirm_child, pending.childName))
            Text(pending.dose)
            ListFetched(pending.list)
            Button(
              onClick = viewModel::confirm,
              enabled = !state.busy,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(Res.string.verify_confirm, pending.childName))
            }
            OutlinedButton(
              onClick = viewModel::decline,
              enabled = !state.busy,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(Res.string.verify_decline))
            }
          }
        }
      }

      if (state.matchChanged) {
        Text(stringResource(Res.string.match_changed), color = MaterialTheme.colorScheme.error)
      }
      state.matchedTo.forEach { Text(stringResource(Res.string.match_done, it.display)) }
      if (state.listStale) Text(stringResource(Res.string.list_stale))
      state.unmatched.forEach { dose ->
        MatchPicker(
          dose = dose,
          choices = state.choices,
          busy = state.busy,
          onMatch = { viewModel.match(dose, it) },
          onNone = { viewModel.leaveUnmapped(dose) },
        )
      }

      if (state.results.isNotEmpty()) {
        Text(
          stringResource(Res.string.verify_results),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      state.results.forEach { Outcome(it) }
      if (state.referral != null && state.unmatched.isEmpty()) {
        Button(
          onClick = onOpenSchedule,
          enabled = !state.busy && state.pending == null,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(Res.string.verify_open_schedule))
        }
      }
    }
  }
}

@Composable
private fun ListFetched(list: ListFreshness) {
  list.fetchedAtEpochSeconds?.let {
    Text(stringResource(Res.string.verify_list_fetched, displayTime(it)))
  }
  if (list.stale) {
    Text(stringResource(Res.string.verify_list_stale), color = MaterialTheme.colorScheme.error)
  }
}

@Composable
private fun Outcome(outcome: ScanOutcome) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      when (outcome) {
        is ScanOutcome.Imported -> {
          Text(
            stringResource(Res.string.verify_imported),
            style = MaterialTheme.typography.labelLarge,
          )
          Text(outcome.dose)
          outcome.list?.let { ListFetched(it) }
          if (outcome.replacedReported) Text(stringResource(Res.string.verify_replaced))
          if (outcome.listStale) Text(stringResource(Res.string.list_stale))
        }
        is ScanOutcome.AlreadyImported -> {
          Text(
            stringResource(Res.string.verify_already_imported),
            style = MaterialTheme.typography.labelLarge,
          )
          Text(outcome.dose)
        }
        is ScanOutcome.HolderMismatch -> {
          Text(
            stringResource(Res.string.verify_other_child),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
          )
          Text(
            stringResource(
              Res.string.verify_birth_dates,
              outcome.cardBirthDate,
              outcome.childBirthDate ?: "-",
            )
          )
          Text(stringResource(Res.string.verify_nothing_imported))
        }
        is ScanOutcome.ImportedForAnotherChild -> {
          Text(
            stringResource(Res.string.verify_imported_elsewhere),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
          )
          Text(outcome.dose)
          Text(stringResource(Res.string.verify_nothing_imported))
        }
        is ScanOutcome.Failed -> {
          Text(
            stringResource(Res.string.verify_failed),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
          )
          Text(
            stringResource(
              Res.string.verify_failed_reason,
              outcome.step.display,
              outcome.reason.display,
            )
          )
          Text(stringResource(Res.string.verify_nothing_imported))
        }
      }
    }
  }
}

@Composable
private fun MatchPicker(
  dose: UnmatchedDose,
  choices: List<EpiDose>,
  busy: Boolean,
  onMatch: (EpiDose) -> Unit,
  onNone: () -> Unit,
) {
  var selected by remember(dose.id) { mutableStateOf<EpiDose?>(null) }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(stringResource(Res.string.match_title), style = MaterialTheme.typography.labelLarge)
      Text(dose.label)
      Text(stringResource(Res.string.match_hint))
      choices.forEach { choice ->
        Row(
          Modifier.fillMaxWidth()
            .selectable(
              selected = selected == choice,
              enabled = !busy,
              role = Role.RadioButton,
              onClick = { selected = choice },
            ),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(selected = selected == choice, onClick = null)
          Text(choice.display, Modifier.padding(start = 8.dp))
        }
      }
      Button(
        onClick = { selected?.let(onMatch) },
        enabled = selected != null && !busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(Res.string.match_confirm, selected?.display ?: "-"))
      }
      OutlinedButton(onClick = onNone, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.match_none))
      }
    }
  }
}
