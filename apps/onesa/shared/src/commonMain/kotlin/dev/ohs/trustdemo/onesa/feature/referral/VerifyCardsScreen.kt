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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import dev.ohs.trustdemo.onesa.data.verification.ScanOutcome
import dev.ohs.trustdemo.onesa.data.verification.display
import dev.ohs.trustdemo.onesa.feature.scan.QrScan
import dev.ohs.trustdemo.onesa.feature.scan.QrScannerView
import dev.ohs.trustdemo.onesa.feature.scan.cameraScanningAvailable
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.scan_back
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_camera
import dev.ohs.trustdemo.onesa.generated.resources.scan_paste_label
import dev.ohs.trustdemo.onesa.generated.resources.verify_already_imported
import dev.ohs.trustdemo.onesa.generated.resources.verify_could_not_save
import dev.ohs.trustdemo.onesa.generated.resources.verify_failed
import dev.ohs.trustdemo.onesa.generated.resources.verify_failed_reason
import dev.ohs.trustdemo.onesa.generated.resources.verify_imported
import dev.ohs.trustdemo.onesa.generated.resources.verify_nothing_imported
import dev.ohs.trustdemo.onesa.generated.resources.verify_replaced
import dev.ohs.trustdemo.onesa.generated.resources.verify_results
import dev.ohs.trustdemo.onesa.generated.resources.verify_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_use_text
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** S4: verifies each of a referred child's cards against the trust list and imports its dose. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyCardsScreen(referralId: String, onBack: () -> Unit) {
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
      if (cameraScanningAvailable) {
        QrScannerView(Modifier.fillMaxWidth().height(280.dp), onResult = onScan)
      } else {
        QrScannerView(Modifier.fillMaxWidth(), onResult = onScan)
      }
      if (cameraFailed) {
        Text(stringResource(Res.string.scan_error_camera), color = MaterialTheme.colorScheme.error)
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

      if (state.results.isNotEmpty()) {
        Text(
          stringResource(Res.string.verify_results),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      state.results.forEach { Outcome(it) }
    }
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
          if (outcome.replacedReported) Text(stringResource(Res.string.verify_replaced))
        }
        is ScanOutcome.AlreadyImported -> {
          Text(
            stringResource(Res.string.verify_already_imported),
            style = MaterialTheme.typography.labelLarge,
          )
          Text(outcome.dose)
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
