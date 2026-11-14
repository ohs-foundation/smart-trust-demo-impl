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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.hcert.DvcFhirMapper
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.registration.ScannedCard
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.scan_back
import dev.ohs.trustdemo.onesa.generated.resources.scan_card_number
import dev.ohs.trustdemo.onesa.generated.resources.scan_continue
import dev.ohs.trustdemo.onesa.generated.resources.scan_decoded
import dev.ohs.trustdemo.onesa.generated.resources.scan_decoded_caption
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_already_read
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_camera
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_no_dose
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_not_a_card
import dev.ohs.trustdemo.onesa.generated.resources.scan_offline_decode
import dev.ohs.trustdemo.onesa.generated.resources.scan_paste_label
import dev.ohs.trustdemo.onesa.generated.resources.scan_prequal_code
import dev.ohs.trustdemo.onesa.generated.resources.scan_read_another
import dev.ohs.trustdemo.onesa.generated.resources.scan_read_card
import dev.ohs.trustdemo.onesa.generated.resources.scan_row_detail
import dev.ohs.trustdemo.onesa.generated.resources.scan_stored_as_issued
import dev.ohs.trustdemo.onesa.generated.resources.scan_title
import dev.ohs.trustdemo.onesa.generated.resources.scan_title_read
import dev.ohs.trustdemo.onesa.generated.resources.scan_unverified_text
import dev.ohs.trustdemo.onesa.generated.resources.scan_unverified_title
import dev.ohs.trustdemo.onesa.generated.resources.scan_works_offline
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.NoteLine
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaTextField
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Reads the family's foreign cards, unverified, before the registration form: the camera and the
 * paste field until a card is read (scan card), then the decoded cards (S2).
 */
@Composable
fun ScanCardScreen(onBack: () -> Unit, onContinue: () -> Unit) {
  val viewModel = koinViewModel<ScanCardViewModel>()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var pasted by rememberSaveable { mutableStateOf("") }
  var reading by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(state.cards.size) { reading = false }
  ScanCardContent(
    state = state,
    reading = reading || state.cards.isEmpty(),
    pasted = pasted,
    onPastedChange = { pasted = it },
    onRead = {
      viewModel.onText(pasted)
      pasted = ""
    },
    onScan = viewModel::onScan,
    onReadAnother = { reading = true },
    onContinue = onContinue,
    onBack = onBack,
  )
}

@Composable
fun ScanCardContent(
  state: ScanCardUiState,
  reading: Boolean,
  pasted: String,
  onPastedChange: (String) -> Unit,
  onRead: () -> Unit,
  onScan: (QrScan) -> Unit,
  onReadAnother: () -> Unit,
  onContinue: () -> Unit,
  onBack: () -> Unit,
) {
  val cards = state.cards
  val child =
    cards.firstOrNull()?.let { DvcFhirMapper.humanName(it.dvc.n).given.firstOrNull()?.value }
  OnesaScaffold(
    topBar = {
      OnesaTopBar(
        title =
          if (cards.isEmpty()) stringResource(Res.string.scan_title)
          else stringResource(Res.string.scan_title_read, cards.size),
        icon = OnesaIcons.Scan,
        status = Status(stringResource(Res.string.scan_offline_decode), Tone.Warning),
        onIconClick = onBack,
        iconLabel = stringResource(Res.string.scan_back),
      )
    },
    actions = {
      if (reading) {
        PrimaryActionBar(
          listOf(
            Action(
              stringResource(Res.string.scan_read_card),
              onClick = onRead,
              enabled = pasted.isNotBlank(),
            )
          )
        )
      } else {
        PrimaryActionBar(
          listOf(
            Action(stringResource(Res.string.scan_continue, child ?: ""), onClick = onContinue),
            Action(
              stringResource(Res.string.scan_read_another),
              onClick = onReadAnother,
              primary = false,
            ),
          ),
          caption = stringResource(Res.string.scan_decoded_caption),
        )
      }
    },
  ) {
    if (reading) {
      CardCameraPanel(
        onResult = onScan,
        caption = stringResource(Res.string.scan_card_number, cards.size + 1),
      )
      state.error?.let { ScanErrorAlert(it) }
      SectionCard {
        OnesaTextField(
          label = stringResource(Res.string.scan_paste_label),
          value = pasted,
          onValueChange = onPastedChange,
          placeholder = "HC1:…",
          mono = true,
          minLines = 2,
        )
      }
      NoteLine(stringResource(Res.string.scan_works_offline))
    } else {
      AlertCard(
        title = stringResource(Res.string.scan_unverified_title),
        text = stringResource(Res.string.scan_unverified_text, child ?: ""),
        tone = Tone.Warning,
      )
      SectionCard(title = stringResource(Res.string.scan_decoded), icon = OnesaIcons.Qr) {
        cards.forEachIndexed { index, card -> DecodedCard(card, index + 1, cards.size) }
      }
    }
  }
}

@Composable
private fun ScanErrorAlert(error: ScanError) {
  AlertCard(
    title = null,
    text =
      stringResource(
        when (error) {
          ScanError.NotACard -> Res.string.scan_error_not_a_card
          ScanError.NoDose -> Res.string.scan_error_no_dose
          ScanError.AlreadyRead -> Res.string.scan_error_already_read
          ScanError.Camera -> Res.string.scan_error_camera
        }
      ),
    tone = Tone.Danger,
  )
}

@Composable
private fun DecodedCard(card: ScannedCard, number: Int, total: Int) {
  val vx = card.dvc.vx
  RecordRow(
    label = NewbornRegistration.PRODUCTS[vx.vp] ?: vx.vp,
    icon = OnesaIcons.Syringe,
    code = stringResource(Res.string.scan_prequal_code, vx.vp),
    detail =
      stringResource(
        Res.string.scan_row_detail,
        number,
        total,
        vx.dv,
        vx.issuerId?.let(NewbornRegistration::countryName) ?: card.dvc.nt,
      ),
    status = Status(stringResource(Res.string.scan_stored_as_issued), Tone.Warning),
  )
}
