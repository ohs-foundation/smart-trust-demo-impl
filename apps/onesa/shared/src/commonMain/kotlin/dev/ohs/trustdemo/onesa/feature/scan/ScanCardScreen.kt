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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.ohs.trustdemo.onesa.generated.resources.scan_none_yet
import dev.ohs.trustdemo.onesa.generated.resources.scan_not_verified
import dev.ohs.trustdemo.onesa.generated.resources.scan_not_verified_note
import dev.ohs.trustdemo.onesa.generated.resources.scan_paste_label
import dev.ohs.trustdemo.onesa.generated.resources.scan_title
import dev.ohs.trustdemo.onesa.generated.resources.scan_use_text
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.NoteLine
import dev.ohs.trustdemo.onesa.ui.OnesaButton
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaTextField
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** S2: reads each card the family holds, unverified, before the registration form. */
@Composable
fun ScanCardScreen(onBack: () -> Unit, onContinue: () -> Unit) {
  val viewModel = koinViewModel<ScanCardViewModel>()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var pasted by remember { mutableStateOf("") }

  OnesaScaffold(
    topBar = {
      OnesaTopBar(
        title = stringResource(Res.string.scan_title),
        icon = OnesaIcons.Scan,
        onIconClick = onBack,
        iconLabel = stringResource(Res.string.scan_back),
      )
    },
    actions = {
      PrimaryActionBar(
        listOf(
          Action(
            stringResource(Res.string.scan_continue),
            onClick = onContinue,
            enabled = state.cards.isNotEmpty(),
          )
        )
      )
    },
  ) {
    CardCameraPanel(onResult = viewModel::onScan)
    state.error?.let {
      AlertCard(
        title = null,
        text =
          stringResource(
            when (it) {
              ScanError.NotACard -> Res.string.scan_error_not_a_card
              ScanError.NoDose -> Res.string.scan_error_no_dose
              ScanError.AlreadyRead -> Res.string.scan_error_already_read
              ScanError.Camera -> Res.string.scan_error_camera
            }
          ),
        tone = Tone.Danger,
      )
    }
    SectionCard {
      OnesaTextField(
        label = stringResource(Res.string.scan_paste_label),
        value = pasted,
        onValueChange = { pasted = it },
        placeholder = "HC1:…",
        mono = true,
        minLines = 2,
      )
      OnesaButton(
        Action(
          stringResource(Res.string.scan_use_text),
          onClick = {
            viewModel.onText(pasted)
            pasted = ""
          },
          primary = false,
          enabled = pasted.isNotBlank(),
        )
      )
    }
    SectionCard(
      title = stringResource(Res.string.scan_cards_read),
      icon = OnesaIcons.Qr,
      summary = Status(state.cards.size.toString(), Tone.Info),
    ) {
      if (state.cards.isEmpty()) {
        Text(
          stringResource(Res.string.scan_none_yet),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      state.cards.forEach { CardSummary(it) }
    }
    if (state.cards.isNotEmpty()) {
      NoteLine(stringResource(Res.string.scan_not_verified_note))
    }
  }
}

@Composable
private fun CardSummary(card: ScannedCard) {
  val dvc = card.dvc
  RecordRow(
    label = NewbornRegistration.PRODUCTS[dvc.vx.vp] ?: dvc.vx.vp,
    icon = OnesaIcons.Syringe,
    code = dvc.vx.vp,
    detail =
      listOfNotNull(
          "${stringResource(Res.string.scan_field_child)} ${DvcFhirMapper.humanName(dvc.n).text?.value ?: dvc.n}",
          "${stringResource(Res.string.scan_field_born)} ${dvc.dob}",
          "${stringResource(Res.string.scan_field_given)} ${dvc.vx.dv}",
          "${stringResource(Res.string.scan_field_lot)} ${dvc.vx.bo}",
          dvc.vx.issuerId?.let { "${stringResource(Res.string.scan_field_issuer)} $it" },
        )
        .joinToString(" • "),
    status = Status(stringResource(Res.string.scan_not_verified), Tone.Warning),
  )
}
