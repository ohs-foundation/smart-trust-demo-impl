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
package dev.ohs.trustdemo.onesa.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.data.sharing.ShareProblem
import dev.ohs.trustdemo.onesa.data.sharing.ShareRequestBody
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.share_agree
import dev.ohs.trustdemo.onesa.generated.resources.share_back
import dev.ohs.trustdemo.onesa.generated.resources.share_choose_date
import dev.ohs.trustdemo.onesa.generated.resources.share_confirm
import dev.ohs.trustdemo.onesa.generated.resources.share_end
import dev.ohs.trustdemo.onesa.generated.resources.share_end_hint
import dev.ohs.trustdemo.onesa.generated.resources.share_ok
import dev.ohs.trustdemo.onesa.generated.resources.share_passcode
import dev.ohs.trustdemo.onesa.generated.resources.share_passcode_again
import dev.ohs.trustdemo.onesa.generated.resources.share_passcode_differ
import dev.ohs.trustdemo.onesa.generated.resources.share_passcode_length
import dev.ohs.trustdemo.onesa.generated.resources.share_people
import dev.ohs.trustdemo.onesa.generated.resources.share_purpose
import dev.ohs.trustdemo.onesa.generated.resources.share_purpose_value
import dev.ohs.trustdemo.onesa.generated.resources.share_sharing
import dev.ohs.trustdemo.onesa.generated.resources.share_terms
import dev.ohs.trustdemo.onesa.generated.resources.share_title
import dev.ohs.trustdemo.onesa.generated.resources.wallet_not_synced
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The share and consent screen: people, the terms, the last day and the passcode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(onBack: () -> Unit, onIssued: (List<String>) -> Unit) {
  val viewModel: ShareViewModel = koinViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  var picking by remember { mutableStateOf(false) }

  LaunchedEffect(state.issued) { state.issued?.let(onIssued) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.share_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(Res.string.share_back),
            )
          }
        },
      )
    }
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .padding(16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(Res.string.share_people), style = MaterialTheme.typography.titleMedium)
      state.members.forEach { person ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
            checked = person.id in state.selected,
            onCheckedChange = { viewModel.toggle(person.id) },
            enabled = person.name != null,
          )
          Text(person.name ?: stringResource(Res.string.wallet_not_synced))
        }
      }

      Text(stringResource(Res.string.share_purpose), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(Res.string.share_purpose_value))
      Text(stringResource(Res.string.share_terms), style = MaterialTheme.typography.titleMedium)
      state.terms.forEach { term ->
        Text(term.display, style = MaterialTheme.typography.titleSmall)
        Text(term.definition, style = MaterialTheme.typography.bodyMedium)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.agreed, onCheckedChange = viewModel::agree)
        Text(stringResource(Res.string.share_agree))
      }

      Text(stringResource(Res.string.share_end), style = MaterialTheme.typography.titleMedium)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(state.end.toString(), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { picking = true }) {
          Text(stringResource(Res.string.share_choose_date))
        }
      }
      Text(
        stringResource(Res.string.share_end_hint, ShareRequestBody.lastDay(state.today).toString()),
        style = MaterialTheme.typography.bodySmall,
      )

      OutlinedTextField(
        value = state.passcode,
        onValueChange = viewModel::passcode,
        label = { Text(stringResource(Res.string.share_passcode)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = state.problem == ShareProblem.PasscodeLength && state.passcode.isNotEmpty(),
        supportingText = {
          if (state.problem == ShareProblem.PasscodeLength && state.passcode.isNotEmpty()) {
            Text(stringResource(Res.string.share_passcode_length))
          }
        },
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = state.again,
        onValueChange = viewModel::again,
        label = { Text(stringResource(Res.string.share_passcode_again)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = state.problem == ShareProblem.PasscodesDiffer && state.again.isNotEmpty(),
        supportingText = {
          if (state.problem == ShareProblem.PasscodesDiffer && state.again.isNotEmpty()) {
            Text(stringResource(Res.string.share_passcode_differ))
          }
        },
        modifier = Modifier.fillMaxWidth(),
      )

      state.refusal?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      Button(
        onClick = viewModel::share,
        enabled = state.canShare,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          stringResource(if (state.sharing) Res.string.share_sharing else Res.string.share_confirm)
        )
      }
    }
  }

  if (picking) {
    val first = state.today
    val last = ShareRequestBody.lastDay(first)
    val pickerState =
      rememberDatePickerState(
        initialSelectedDateMillis = state.end.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        selectableDates =
          object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
              utcTimeMillis.toUtcDate() in first..last
          },
      )
    DatePickerDialog(
      onDismissRequest = { picking = false },
      confirmButton = {
        TextButton(
          onClick = {
            pickerState.selectedDateMillis?.let { viewModel.end(it.toUtcDate()) }
            picking = false
          }
        ) {
          Text(stringResource(Res.string.share_ok))
        }
      },
    ) {
      DatePicker(state = pickerState)
    }
  }
}

private fun Long.toUtcDate(): LocalDate =
  Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date
