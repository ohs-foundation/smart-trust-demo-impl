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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.data.sharing.Look
import dev.ohs.trustdemo.onesa.data.sharing.Refusal
import dev.ohs.trustdemo.onesa.data.sharing.ShareStatus
import dev.ohs.trustdemo.onesa.data.sharing.SharedLink
import dev.ohs.trustdemo.onesa.data.sharing.WithdrawOutcome
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.share_back
import dev.ohs.trustdemo.onesa.generated.resources.shared_clinic_unknown
import dev.ohs.trustdemo.onesa.generated.resources.shared_closed
import dev.ohs.trustdemo.onesa.generated.resources.shared_empty
import dev.ohs.trustdemo.onesa.generated.resources.shared_from_other_device
import dev.ohs.trustdemo.onesa.generated.resources.shared_look_document_released
import dev.ohs.trustdemo.onesa.generated.resources.shared_look_refused
import dev.ohs.trustdemo.onesa.generated.resources.shared_look_released
import dev.ohs.trustdemo.onesa.generated.resources.shared_looks
import dev.ohs.trustdemo.onesa.generated.resources.shared_no_looks
import dev.ohs.trustdemo.onesa.generated.resources.shared_notice_changed
import dev.ohs.trustdemo.onesa.generated.resources.shared_notice_gone
import dev.ohs.trustdemo.onesa.generated.resources.shared_notice_not_yours
import dev.ohs.trustdemo.onesa.generated.resources.shared_notice_withdrawn
import dev.ohs.trustdemo.onesa.generated.resources.shared_open
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_attempts_exhausted
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_expired
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_other
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_withdrawn
import dev.ohs.trustdemo.onesa.generated.resources.shared_refusal_wrong_passcode
import dev.ohs.trustdemo.onesa.generated.resources.shared_show_qr
import dev.ohs.trustdemo.onesa.generated.resources.shared_status_expired
import dev.ohs.trustdemo.onesa.generated.resources.shared_status_withdrawn
import dev.ohs.trustdemo.onesa.generated.resources.shared_title
import dev.ohs.trustdemo.onesa.generated.resources.shared_until
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw_body
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw_cancel
import dev.ohs.trustdemo.onesa.generated.resources.shared_withdraw_title
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Every share the household made: who it is for, until when, who looked, and the two actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLinksScreen(onBack: () -> Unit, onShowQr: (String) -> Unit) {
  val viewModel: SharedLinksViewModel = koinViewModel()
  val links by viewModel.links.collectAsStateWithLifecycle()
  val notice by viewModel.notice.collectAsStateWithLifecycle()
  var confirming by remember { mutableStateOf<String?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.shared_title)) },
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
    val shares = links
    if (shares == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return@Scaffold
    }
    val (open, closed) = shares.partition { it.status == ShareStatus.Active }
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      notice?.let {
        item {
          val color =
            if (it == WithdrawOutcome.Withdrawn) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error
          Text(noticeText(it), color = color)
        }
      }
      if (shares.isEmpty()) item { Text(stringResource(Res.string.shared_empty)) }
      if (open.isNotEmpty()) {
        item {
          Text(stringResource(Res.string.shared_open), style = MaterialTheme.typography.titleMedium)
        }
        items(open, key = { it.consentId }) {
          ShareCard(
            it,
            onShowQr = { onShowQr(it.consentId) },
            onWithdraw = { confirming = it.consentId },
          )
        }
      }
      if (closed.isNotEmpty()) {
        item {
          Text(
            stringResource(Res.string.shared_closed),
            style = MaterialTheme.typography.titleMedium,
          )
        }
        items(closed, key = { it.consentId }) { ShareCard(it, onShowQr = {}, onWithdraw = {}) }
      }
    }
  }

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

@Composable
private fun ShareCard(share: SharedLink, onShowQr: () -> Unit, onWithdraw: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(share.name ?: share.personId, style = MaterialTheme.typography.titleMedium)
    Text(
      when (share.status) {
        ShareStatus.Active -> stringResource(Res.string.shared_until, share.end.toString())
        ShareStatus.Withdrawn -> stringResource(Res.string.shared_status_withdrawn)
        ShareStatus.Expired -> stringResource(Res.string.shared_status_expired)
      },
      style = MaterialTheme.typography.bodyMedium,
    )
    Text(stringResource(Res.string.shared_looks), style = MaterialTheme.typography.labelLarge)
    if (share.looks.isEmpty()) Text(stringResource(Res.string.shared_no_looks))
    share.looks.forEach { Text(lookText(it), style = MaterialTheme.typography.bodySmall) }
    if (share.status == ShareStatus.Active) {
      if (!share.kept) Text(stringResource(Res.string.shared_from_other_device))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onShowQr, enabled = share.kept) {
          Text(stringResource(Res.string.shared_show_qr))
        }
        OutlinedButton(onClick = onWithdraw) { Text(stringResource(Res.string.shared_withdraw)) }
      }
    }
    HorizontalDivider()
  }
}

@Composable
private fun lookText(look: Look): String {
  val at =
    look.at.toLocalDateTime(TimeZone.UTC).let {
      "${it.date} ${it.hour.pad()}:${it.minute.pad()} (UTC)"
    }
  val clinic = look.clinic ?: stringResource(Res.string.shared_clinic_unknown)
  return when {
    !look.released ->
      stringResource(
        Res.string.shared_look_refused,
        at,
        clinic,
        stringResource(look.refusal.text()),
      )
    look.document -> stringResource(Res.string.shared_look_document_released, at, clinic)
    else -> stringResource(Res.string.shared_look_released, at, clinic)
  }
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
    WithdrawOutcome.Withdrawn -> stringResource(Res.string.shared_notice_withdrawn)
    WithdrawOutcome.Changed -> stringResource(Res.string.shared_notice_changed)
    WithdrawOutcome.NotYours -> stringResource(Res.string.shared_notice_not_yours)
    WithdrawOutcome.Gone -> stringResource(Res.string.shared_notice_gone)
    is WithdrawOutcome.Failed -> outcome.message
  }
