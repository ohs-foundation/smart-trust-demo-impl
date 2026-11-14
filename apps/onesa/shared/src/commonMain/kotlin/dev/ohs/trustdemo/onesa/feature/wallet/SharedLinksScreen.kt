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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.data.sharing.Look
import dev.ohs.trustdemo.onesa.data.sharing.Refusal
import dev.ohs.trustdemo.onesa.data.sharing.ShareStatus
import dev.ohs.trustdemo.onesa.data.sharing.SharedLink
import dev.ohs.trustdemo.onesa.data.sharing.WithdrawOutcome
import dev.ohs.trustdemo.onesa.feature.home.RoleTopBar
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.shared_attempts
import dev.ohs.trustdemo.onesa.generated.resources.shared_audit_footnote
import dev.ohs.trustdemo.onesa.generated.resources.shared_clinic_unknown
import dev.ohs.trustdemo.onesa.generated.resources.shared_empty
import dev.ohs.trustdemo.onesa.generated.resources.shared_failed_attempts
import dev.ohs.trustdemo.onesa.generated.resources.shared_from_other_device
import dev.ohs.trustdemo.onesa.generated.resources.shared_look_document
import dev.ohs.trustdemo.onesa.generated.resources.shared_look_refused
import dev.ohs.trustdemo.onesa.generated.resources.shared_look_released
import dev.ohs.trustdemo.onesa.generated.resources.shared_no_looks
import dev.ohs.trustdemo.onesa.generated.resources.shared_notice_changed
import dev.ohs.trustdemo.onesa.generated.resources.shared_notice_gone
import dev.ohs.trustdemo.onesa.generated.resources.shared_notice_not_yours
import dev.ohs.trustdemo.onesa.generated.resources.shared_notice_withdrawn
import dev.ohs.trustdemo.onesa.generated.resources.shared_notice_withdrawn_stale
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_attempts_exhausted
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_expired
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_other
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_withdrawn
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_wrong_passcode
import dev.ohs.trustdemo.onesa.generated.resources.shared_refused
import dev.ohs.trustdemo.onesa.generated.resources.shared_released
import dev.ohs.trustdemo.onesa.generated.resources.shared_show_all
import dev.ohs.trustdemo.onesa.generated.resources.shared_show_latest
import dev.ohs.trustdemo.onesa.generated.resources.shared_show_qr
import dev.ohs.trustdemo.onesa.generated.resources.shared_status
import dev.ohs.trustdemo.onesa.generated.resources.shared_status_expired
import dev.ohs.trustdemo.onesa.generated.resources.shared_status_withdrawn
import dev.ohs.trustdemo.onesa.generated.resources.shared_subtitle
import dev.ohs.trustdemo.onesa.generated.resources.shared_until
import dev.ohs.trustdemo.onesa.generated.resources.shared_who_opened
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw_body
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw_cancel
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw_caption
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw_link
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw_title
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.Field
import dev.ohs.trustdemo.onesa.ui.FieldGrid
import dev.ohs.trustdemo.onesa.ui.NoteLine
import dev.ohs.trustdemo.onesa.ui.OnesaButton
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.PillTabs
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.displayDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 1-19: each share the household made, who opened it, and withdrawing it. */
@Composable
fun SharedLinksScreen(onBack: () -> Unit, onShowQr: (String) -> Unit) {
  val viewModel: SharedLinksViewModel = koinViewModel()
  val links by viewModel.links.collectAsStateWithLifecycle()
  val notice by viewModel.notice.collectAsStateWithLifecycle()
  var confirming by remember { mutableStateOf<String?>(null) }
  var showingAll by remember { mutableStateOf(emptySet<String>()) }
  var selected by rememberSaveable { mutableIntStateOf(0) }
  val shares = links
  if (shares == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }
  val ordered = shares.sortedBy { it.status != ShareStatus.Active }
  val index = selected.coerceIn(0, (ordered.size - 1).coerceAtLeast(0))
  SharedLinksContent(
    shares = ordered,
    index = index,
    onIndex = { selected = it },
    notice = notice,
    showAll = ordered.getOrNull(index)?.consentId in showingAll,
    onToggleAll = { id -> showingAll = if (id in showingAll) showingAll - id else showingAll + id },
    topBar = { subtitle -> RoleTopBar(AppRole.FAMILY, onBack, subtitle) },
    onShowQr = onShowQr,
    onWithdraw = { confirming = it },
  )
  confirming?.let { consentId ->
    AlertDialog(
      onDismissRequest = { confirming = null },
      title = { Text(stringResource(Res.string.shared_withdraw_title)) },
      text = { Text(stringResource(Res.string.shared_withdraw_body)) },
      confirmButton = {
        Button(
          onClick = {
            confirming = null
            viewModel.dismissNotice()
            viewModel.withdraw(consentId)
          }
        ) {
          Text(stringResource(Res.string.shared_withdraw))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirming = null }) {
          Text(stringResource(Res.string.shared_withdraw_cancel))
        }
      },
    )
  }
}

/** How many of a share's latest looks show before the family asks for all of them. */
internal const val LATEST_LOOKS = 5

/** One share at a time, picked from the tabs: who opened its link, until when, and its actions. */
@Composable
internal fun SharedLinksContent(
  shares: List<SharedLink>,
  index: Int,
  onIndex: (Int) -> Unit,
  notice: WithdrawOutcome?,
  showAll: Boolean,
  onToggleAll: (String) -> Unit,
  topBar: @Composable (subtitle: String?) -> Unit,
  onShowQr: (String) -> Unit,
  onWithdraw: (String) -> Unit,
) {
  val share = shares.getOrNull(index)
  val name = share?.let { it.name ?: it.personId }
  OnesaScaffold(
    topBar = { topBar(name?.let { stringResource(Res.string.shared_subtitle, it) }) },
    actions =
      share
        ?.takeIf { it.status == ShareStatus.Active }
        ?.let {
          {
            PrimaryActionBar(
              listOf(
                Action(
                  stringResource(Res.string.shared_show_qr),
                  { onShowQr(it.consentId) },
                  primary = false,
                  enabled = it.kept,
                ),
                Action(
                  stringResource(Res.string.shared_withdraw_link),
                  { onWithdraw(it.consentId) },
                  primary = false,
                ),
              ),
              caption = stringResource(Res.string.shared_withdraw_caption),
            )
          }
        },
  ) {
    if (shares.size > 1) PillTabs(shares.map { it.name ?: it.personId }, index, onIndex)
    notice?.let {
      AlertCard(
        null,
        noticeText(it),
        if (it is WithdrawOutcome.Withdrawn) Tone.Success else Tone.Danger,
      )
    }
    if (share == null) {
      Text(
        stringResource(Res.string.shared_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = OnesaColors.Muted,
      )
      return@OnesaScaffold
    }
    val shown = if (showAll) share.looks else share.looks.take(LATEST_LOOKS)
    SectionCard(
      title = stringResource(Res.string.shared_who_opened, name.orEmpty()),
      summary = Status(stringResource(Res.string.shared_attempts, share.looks.size), Tone.Info),
      footnote =
        shown
          .takeIf { it.isNotEmpty() }
          ?.let {
            stringResource(Res.string.shared_audit_footnote, it.joinToString(" and ") { l -> l.id })
          },
    ) {
      if (share.looks.isEmpty()) {
        Text(
          stringResource(Res.string.shared_no_looks),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      shown.forEach { LookRow(it) }
      if (share.looks.size > LATEST_LOOKS) {
        OnesaButton(
          Action(
            if (showAll) stringResource(Res.string.shared_show_latest, LATEST_LOOKS)
            else stringResource(Res.string.shared_show_all, share.looks.size),
            { onToggleAll(share.consentId) },
            primary = false,
          )
        )
      }
    }
    SectionCard {
      FieldGrid(
        listOf(
          when (share.status) {
            ShareStatus.Active ->
              Field(stringResource(Res.string.shared_until), displayDate(share.end))
            ShareStatus.Withdrawn ->
              Field(
                stringResource(Res.string.shared_status),
                stringResource(Res.string.shared_status_withdrawn),
              )
            ShareStatus.Expired ->
              Field(
                stringResource(Res.string.shared_status),
                stringResource(Res.string.shared_status_expired),
              )
          },
          Field(
            stringResource(Res.string.shared_failed_attempts),
            share.looks.count { it.refusal == Refusal.WrongPasscode }.toString(),
          ),
        ),
        columns = 2,
      )
    }
    if (share.status == ShareStatus.Active && !share.kept) {
      NoteLine(stringResource(Res.string.shared_from_other_device))
    }
  }
}

@Composable
private fun LookRow(look: Look) {
  val at =
    look.at.toLocalDateTime(TimeZone.currentSystemDefault()).let {
      "${displayDate(it.date)}, ${it.hour.pad()}:${it.minute.pad()}"
    }
  RecordRow(
    label = look.clinic ?: stringResource(Res.string.shared_clinic_unknown),
    code = look.host,
    detail =
      "$at • " +
        when {
          !look.released ->
            stringResource(Res.string.shared_look_refused, stringResource(look.refusal.text()))
          look.document -> stringResource(Res.string.shared_look_document)
          else -> stringResource(Res.string.shared_look_released)
        },
    status =
      if (look.released) Status(stringResource(Res.string.shared_released), Tone.Success)
      else Status(stringResource(Res.string.shared_refused), Tone.Danger),
  )
}

private fun Int.pad() = toString().padStart(2, '0')

private fun Refusal?.text(): StringResource =
  when (this) {
    Refusal.Withdrawn -> Res.string.shared_refusal_withdrawn
    Refusal.Expired -> Res.string.shared_refusal_expired
    Refusal.WrongPasscode -> Res.string.shared_refusal_wrong_passcode
    Refusal.AttemptsExhausted -> Res.string.shared_refusal_attempts_exhausted
    Refusal.Other,
    null -> Res.string.shared_refusal_other
  }

@Composable
private fun noticeText(outcome: WithdrawOutcome): String =
  when (outcome) {
    is WithdrawOutcome.Withdrawn ->
      stringResource(
        if (outcome.listStale) Res.string.shared_notice_withdrawn_stale
        else Res.string.shared_notice_withdrawn
      )
    WithdrawOutcome.Changed -> stringResource(Res.string.shared_notice_changed)
    WithdrawOutcome.NotYours -> stringResource(Res.string.shared_notice_not_yours)
    WithdrawOutcome.Gone -> stringResource(Res.string.shared_notice_gone)
    is WithdrawOutcome.Failed -> outcome.message
  }
