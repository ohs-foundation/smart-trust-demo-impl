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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.data.sharing.ShareProblem
import dev.ohs.trustdemo.onesa.data.sharing.ShareRequestBody
import dev.ohs.trustdemo.onesa.feature.home.RoleTopBar
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.share_again_title
import dev.ohs.trustdemo.onesa.generated.resources.share_agree_text
import dev.ohs.trustdemo.onesa.generated.resources.share_agree_title
import dev.ohs.trustdemo.onesa.generated.resources.share_caption
import dev.ohs.trustdemo.onesa.generated.resources.share_confirm
import dev.ohs.trustdemo.onesa.generated.resources.share_end
import dev.ohs.trustdemo.onesa.generated.resources.share_end_value
import dev.ohs.trustdemo.onesa.generated.resources.share_for
import dev.ohs.trustdemo.onesa.generated.resources.share_for_none
import dev.ohs.trustdemo.onesa.generated.resources.share_hidden
import dev.ohs.trustdemo.onesa.generated.resources.share_ok
import dev.ohs.trustdemo.onesa.generated.resources.share_passcode
import dev.ohs.trustdemo.onesa.generated.resources.share_passcode_differ
import dev.ohs.trustdemo.onesa.generated.resources.share_passcode_note
import dev.ohs.trustdemo.onesa.generated.resources.share_people
import dev.ohs.trustdemo.onesa.generated.resources.share_purpose
import dev.ohs.trustdemo.onesa.generated.resources.share_purpose_value
import dev.ohs.trustdemo.onesa.generated.resources.share_shown
import dev.ohs.trustdemo.onesa.generated.resources.share_subtitle
import dev.ohs.trustdemo.onesa.generated.resources.share_what
import dev.ohs.trustdemo.onesa.generated.resources.share_what_value
import dev.ohs.trustdemo.onesa.generated.resources.wallet_not_synced
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.FormLabel
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaSelectField
import dev.ohs.trustdemo.onesa.ui.OnesaTextField
import dev.ohs.trustdemo.onesa.ui.OptionRow
import dev.ohs.trustdemo.onesa.ui.PasscodeCard
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.displayDate
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private const val PASSCODE_DIGITS = 4

/**
 * 1-18, share and consent: who travels, the purpose, how long, the passcode said aloud (typed
 * twice) and the terms the holder agrees to by creating the links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(onBack: () -> Unit, onIssued: (List<String>) -> Unit) {
  val viewModel: ShareViewModel = koinViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  var picking by remember { mutableStateOf(false) }

  LaunchedEffect(state.issued) { state.issued?.let(onIssued) }

  ShareContent(
    state = state,
    topBar = { RoleTopBar(AppRole.FAMILY, onBack, stringResource(Res.string.share_subtitle)) },
    onToggle = viewModel::toggle,
    onPickEnd = { picking = true },
    onPasscode = viewModel::passcode,
    onAgain = viewModel::again,
    onAgreeAndShare = {
      viewModel.agree(true)
      viewModel.share()
    },
  )

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

@Composable
internal fun ShareContent(
  state: ShareUiState,
  topBar: @Composable () -> Unit,
  onToggle: (String) -> Unit,
  onPickEnd: () -> Unit,
  onPasscode: (String) -> Unit,
  onAgain: (String) -> Unit,
  onAgreeAndShare: () -> Unit,
) {
  var shown by remember { mutableStateOf(false) }
  val until = displayDate(state.end)
  OnesaScaffold(
    topBar = topBar,
    actions = {
      PrimaryActionBar(
        listOf(
          Action(
            stringResource(Res.string.share_confirm, state.selected.size),
            onClick = onAgreeAndShare,
            busy = state.sharing,
            enabled = state.problem == null && !state.sharing,
          )
        ),
        caption = stringResource(Res.string.share_caption),
        lead = {
          SharingFor(
            state.family.filter { it.id in state.selected }.mapNotNull { it.given ?: it.name }
          )
        },
      )
    },
  ) {
    SectionCard {
      FormLabel(stringResource(Res.string.share_people))
      state.family.forEach { member ->
        OptionRow(
          label = member.given ?: member.name ?: stringResource(Res.string.wallet_not_synced),
          selected = member.id in state.selected,
          onClick = { if (member.name != null) onToggle(member.id) },
          check = true,
          code = relationLine(member, state.today),
        )
      }
      val purpose = stringResource(Res.string.share_purpose_value, ShareRequestBody.PURPOSE)
      OnesaSelectField(stringResource(Res.string.share_purpose), purpose, listOf(purpose), {})
      OnesaSelectField(
        stringResource(Res.string.share_end),
        stringResource(Res.string.share_end_value, until, state.today.daysUntil(state.end)),
        emptyList(),
        {},
        onOpen = onPickEnd,
      )
      OnesaTextField(
        stringResource(Res.string.share_what),
        stringResource(Res.string.share_what_value),
        {},
        readOnly = true,
      )
    }
    PasscodeCard(
      title = stringResource(Res.string.share_passcode, PASSCODE_DIGITS),
      value = state.passcode,
      shown = shown,
      onShownChange = { shown = it },
      shownLabel = stringResource(Res.string.share_shown),
      hiddenLabel = stringResource(Res.string.share_hidden),
      length = PASSCODE_DIGITS,
      note = stringResource(Res.string.share_passcode_note),
      onValueChange = onPasscode,
    )
    if (state.passcode.length == PASSCODE_DIGITS) {
      PasscodeCard(
        title = stringResource(Res.string.share_again_title),
        value = state.again,
        shown = shown,
        onShownChange = { shown = it },
        shownLabel = stringResource(Res.string.share_shown),
        hiddenLabel = stringResource(Res.string.share_hidden),
        length = PASSCODE_DIGITS,
        note =
          if (
            state.problem == ShareProblem.PasscodesDiffer && state.again.length == PASSCODE_DIGITS
          )
            stringResource(Res.string.share_passcode_differ)
          else null,
        onValueChange = onAgain,
      )
    }
    AlertCard(
      stringResource(Res.string.share_agree_title),
      (state.terms.map { it.definition } + stringResource(Res.string.share_agree_text, until))
        .joinToString(" "),
      Tone.Info,
    )
    state.refusal?.let { AlertCard(null, it, Tone.Danger) }
  }
}

/** Who the links are for, by name, or a prompt to choose when no one is ticked. */
@Composable
private fun SharingFor(names: List<String>) {
  val ink = if (names.isEmpty()) Tone.Warning.ink else OnesaColors.Ink
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      if (names.isEmpty()) OnesaIcons.Alert else OnesaIcons.Users,
      null,
      tint = if (names.isEmpty()) ink else MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(16.dp),
    )
    Text(
      if (names.isEmpty()) stringResource(Res.string.share_for_none)
      else stringResource(Res.string.share_for, names.joinToString(", ")),
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = ink,
    )
  }
}

private fun Long.toUtcDate(): LocalDate =
  Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date
