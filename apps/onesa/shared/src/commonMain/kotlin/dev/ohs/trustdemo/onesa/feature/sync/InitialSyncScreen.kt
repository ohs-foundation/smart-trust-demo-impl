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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.feature.role.label
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_continue
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_failed_body
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_failed_title
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_offline_note
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_place
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_records
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_retry
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_signed_in_as
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_steps_done
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_title
import dev.ohs.trustdemo.onesa.generated.resources.initial_sync_your_records
import dev.ohs.trustdemo.onesa.generated.resources.login_brand
import dev.ohs.trustdemo.onesa.generated.resources.login_ministry
import dev.ohs.trustdemo.onesa.generated.resources.sync_more
import dev.ohs.trustdemo.onesa.generated.resources.sync_none_yet
import dev.ohs.trustdemo.onesa.generated.resources.sync_state_done
import dev.ohs.trustdemo.onesa.generated.resources.sync_state_downloading
import dev.ohs.trustdemo.onesa.generated.resources.sync_state_waiting
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.NoteLine
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource

/**
 * The first sync, between sign-in and Home: what the role downloads, part by part, with a way to go
 * on offline. On failure it says so and offers a retry. Never shown for
 * [InitialSyncGateState.Passed]; `App` shows Home instead.
 */
@Composable
fun InitialSyncScreen(
  state: InitialSyncGateState,
  onRetry: () -> Unit,
  onContinueAnyway: () -> Unit,
  progress: SyncStepsState = SyncStepsState(),
  userName: String? = null,
  role: AppRole? = null,
) {
  val failed = state == InitialSyncGateState.Failed
  OnesaScaffold(
    topBar = {
      OnesaTopBar(
        title = stringResource(Res.string.login_brand),
        icon = OnesaIcons.Mark,
        subtitle =
          if (userName != null && role != null) {
            stringResource(
              Res.string.initial_sync_signed_in_as,
              userName,
              stringResource(role.label),
            )
          } else stringResource(Res.string.login_ministry),
      )
    },
    actions = {
      PrimaryActionBar(
        listOfNotNull(
          Action(
            stringResource(Res.string.initial_sync_continue),
            onClick = onContinueAnyway,
            primary = false,
          ),
          if (failed) Action(stringResource(Res.string.initial_sync_retry), onClick = onRetry)
          else null,
        )
      )
    },
  ) {
    if (failed) {
      AlertCard(
        title = stringResource(Res.string.initial_sync_failed_title),
        text = stringResource(Res.string.initial_sync_failed_body),
        tone = Tone.Danger,
      )
    }
    val steps = progress.steps
    SectionCard(
      title =
        progress.place?.let { stringResource(Res.string.initial_sync_place, it) }
          ?: stringResource(Res.string.initial_sync_your_records),
      summary =
        if (steps.isEmpty()) null
        else
          Status(
            stringResource(
              Res.string.initial_sync_steps_done,
              steps.count { it.state == StepState.Done },
              steps.size,
            ),
            Tone.Info,
          ),
    ) {
      if (steps.isEmpty()) {
        Text(
          stringResource(Res.string.initial_sync_title),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      steps.forEach { StepRow(it) }
    }
    NoteLine(stringResource(Res.string.initial_sync_offline_note))
  }
}

@Composable
private fun StepRow(step: SyncStep) {
  val isCode = step.part == SyncPart.Practitioner
  RecordRow(
    label = stringResource(step.part.label),
    code = if (isCode) step.names.firstOrNull() else null,
    detail =
      when {
        step.state == StepState.Waiting || isCode -> null
        step.names.size > 3 ->
          (step.names.take(3) + stringResource(Res.string.sync_more, step.names.size - 3))
            .joinToString(" • ")
        step.names.isNotEmpty() -> step.names.joinToString(" • ")
        step.count > 0 -> stringResource(Res.string.initial_sync_records, step.count)
        step.state == StepState.Done -> stringResource(Res.string.sync_none_yet)
        else -> null
      },
    status =
      when (step.state) {
        StepState.Done -> Status(stringResource(Res.string.sync_state_done), Tone.Success)
        StepState.Downloading ->
          Status(stringResource(Res.string.sync_state_downloading), Tone.Info)
        StepState.Waiting -> Status(stringResource(Res.string.sync_state_waiting))
      },
  )
}
