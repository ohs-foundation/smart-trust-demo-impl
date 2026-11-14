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
package dev.ohs.trustdemo.onesa.feature.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.hcert.DvcFhirMapper
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.registration.ScannedCard
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.scan_back
import dev.ohs.trustdemo.onesa.generated.resources.scan_cards_read
import dev.ohs.trustdemo.onesa.generated.resources.scan_continue
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_already_read
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_camera
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_no_dose
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_not_a_card
import dev.ohs.trustdemo.onesa.generated.resources.scan_field_born
import dev.ohs.trustdemo.onesa.generated.resources.scan_field_child
import dev.ohs.trustdemo.onesa.generated.resources.scan_field_given
import dev.ohs.trustdemo.onesa.generated.resources.scan_field_issuer
import dev.ohs.trustdemo.onesa.generated.resources.scan_field_lot
import dev.ohs.trustdemo.onesa.generated.resources.scan_field_vaccine
import dev.ohs.trustdemo.onesa.generated.resources.scan_none_yet
import dev.ohs.trustdemo.onesa.generated.resources.scan_not_verified
import dev.ohs.trustdemo.onesa.generated.resources.scan_not_verified_note
import dev.ohs.trustdemo.onesa.generated.resources.scan_paste_label
import dev.ohs.trustdemo.onesa.generated.resources.scan_title
import dev.ohs.trustdemo.onesa.generated.resources.scan_use_text
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** S2: reads each card the family holds, unverified, before the registration form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanCardScreen(onBack: () -> Unit, onContinue: () -> Unit) {
  val viewModel = koinViewModel<ScanCardViewModel>()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var pasted by remember { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.scan_title)) },
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
      if (cameraScanningAvailable) {
        QrScannerView(Modifier.fillMaxWidth().height(280.dp), onResult = viewModel::onScan)
      } else {
        QrScannerView(Modifier.fillMaxWidth(), onResult = viewModel::onScan)
      }
      state.error?.let {
        Text(
          stringResource(
            when (it) {
              ScanError.NotACard -> Res.string.scan_error_not_a_card
              ScanError.NoDose -> Res.string.scan_error_no_dose
              ScanError.AlreadyRead -> Res.string.scan_error_already_read
              ScanError.Camera -> Res.string.scan_error_camera
            }
          ),
          color = MaterialTheme.colorScheme.error,
        )
      }
      OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        label = { Text(stringResource(Res.string.scan_paste_label)) },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedButton(
        onClick = {
          viewModel.onText(pasted)
          pasted = ""
        },
        enabled = pasted.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(Res.string.scan_use_text))
      }

      Text(stringResource(Res.string.scan_cards_read), style = MaterialTheme.typography.titleMedium)
      if (state.cards.isEmpty()) {
        Text(stringResource(Res.string.scan_none_yet))
      }
      state.cards.forEach { CardSummary(it) }

      Button(
        onClick = onContinue,
        enabled = state.cards.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(Res.string.scan_continue))
      }
    }
  }
}

@Composable
private fun CardSummary(card: ScannedCard) {
  val dvc = card.dvc
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        stringResource(Res.string.scan_not_verified),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
      )
      Field(
        stringResource(Res.string.scan_field_child),
        DvcFhirMapper.humanName(dvc.n).text?.value ?: dvc.n,
      )
      Field(stringResource(Res.string.scan_field_born), dvc.dob)
      Field(
        stringResource(Res.string.scan_field_vaccine),
        NewbornRegistration.PRODUCTS[dvc.vx.vp] ?: dvc.vx.vp,
      )
      Field(stringResource(Res.string.scan_field_given), dvc.vx.dv)
      Field(stringResource(Res.string.scan_field_lot), dvc.vx.bo)
      dvc.vx.issuerId?.let { Field(stringResource(Res.string.scan_field_issuer), it) }
      Text(
        stringResource(Res.string.scan_not_verified_note),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun Field(label: String, value: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}
