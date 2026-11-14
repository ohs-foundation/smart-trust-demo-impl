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
package dev.ohs.trustdemo.onesa.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.home_cancel_sync
import dev.ohs.trustdemo.onesa.generated.resources.home_sync_in_progress
import dev.ohs.trustdemo.onesa.generated.resources.home_sync_now
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_catchment_only
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_days_ago
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_facility_only
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_keeps
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_keeps_value
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_last_upload
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_never
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_nothing
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_nothing_body
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_queued
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_records
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_refused
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_signal
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_today
import dev.ohs.trustdemo.onesa.generated.resources.sync_status_waiting
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.Field
import dev.ohs.trustdemo.onesa.ui.FieldGrid
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** The sync tab: what waits to upload, when the phone last synced, and where uploads go. */
@Composable
fun SyncStatusContent(
  pending: List<PendingUpload>,
  lastSync: Instant?,
  role: AppRole,
  modifier: Modifier = Modifier,
  now: Instant = Clock.System.now(),
) {
  Column(
    modifier.verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SectionCard(
      title = stringResource(Res.string.sync_status_waiting),
      summary =
        if (pending.isEmpty()) Status(stringResource(Res.string.sync_status_nothing), Tone.Success)
        else Status(stringResource(Res.string.sync_status_records, pending.size), Tone.Warning),
    ) {
      if (pending.isEmpty()) {
        Text(
          stringResource(Res.string.sync_status_nothing_body),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      val queued = Status(stringResource(Res.string.sync_status_queued))
      val refused = Status(stringResource(Res.string.sync_status_refused), Tone.Danger)
      pending.forEach {
        RecordRow(
          it.label,
          code = it.reference,
          detail = it.reason,
          status = if (it.refused) refused else queued,
        )
      }
    }
    SectionCard {
      FieldGrid(
        listOf(
          Field(stringResource(Res.string.sync_status_last_upload), lastSyncText(lastSync, now)),
          Field(
            stringResource(Res.string.sync_status_keeps),
            stringResource(Res.string.sync_status_keeps_value),
          ),
        ),
        columns = 2,
      )
    }
    AlertCard(
      title = stringResource(Res.string.sync_status_signal),
      text =
        stringResource(
          if (role == AppRole.CHW) Res.string.sync_status_catchment_only
          else Res.string.sync_status_facility_only
        ),
      tone = Tone.Info,
    )
  }
}

/** The sync tab's action: sync now, or cancel the sync that is running. */
@Composable
fun syncAction(syncing: Boolean, onSyncNow: () -> Unit, onCancel: () -> Unit): Action =
  if (syncing) {
    Action(
      stringResource(Res.string.home_cancel_sync),
      onClick = onCancel,
      primary = false,
      busy = true,
      busyLabel = stringResource(Res.string.home_sync_in_progress),
    )
  } else {
    Action(stringResource(Res.string.home_sync_now), onClick = onSyncNow)
  }

@Composable
private fun lastSyncText(lastSync: Instant?, now: Instant): String {
  lastSync ?: return stringResource(Res.string.sync_status_never)
  val zone = TimeZone.currentSystemDefault()
  val at = lastSync.toLocalDateTime(zone)
  val days = at.date.daysUntil(now.toLocalDateTime(zone).date)
  val time = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
  return if (days <= 0) stringResource(Res.string.sync_status_today, time)
  else stringResource(Res.string.sync_status_days_ago, dayMonthYear(at.date), days)
}

private fun dayMonthYear(date: LocalDate): String =
  "${date.day} ${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.year}"
