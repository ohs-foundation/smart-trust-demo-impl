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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleDose
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleItem
import dev.ohs.trustdemo.onesa.data.schedule.Standing
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.scan_back
import dev.ohs.trustdemo.onesa.generated.resources.schedule_book
import dev.ohs.trustdemo.onesa.generated.resources.schedule_booked
import dev.ohs.trustdemo.onesa.generated.resources.schedule_complete
import dev.ohs.trustdemo.onesa.generated.resources.schedule_could_not
import dev.ohs.trustdemo.onesa.generated.resources.schedule_could_not_load
import dev.ohs.trustdemo.onesa.generated.resources.schedule_due
import dev.ohs.trustdemo.onesa.generated.resources.schedule_not_yet
import dev.ohs.trustdemo.onesa.generated.resources.schedule_referral_changed
import dev.ohs.trustdemo.onesa.generated.resources.schedule_reported
import dev.ohs.trustdemo.onesa.generated.resources.schedule_title
import dev.ohs.trustdemo.onesa.generated.resources.schedule_unmapped
import dev.ohs.trustdemo.onesa.generated.resources.schedule_unreadable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * S5: the national schedule evaluated for the child, and the booking of the visit it recommends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(referralId: String, onBack: () -> Unit) {
  val viewModel = koinViewModel<ScheduleViewModel>(key = referralId) { parametersOf(referralId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            state.referral?.let {
              "${stringResource(Res.string.schedule_title)}: ${it.patientName}"
            } ?: stringResource(Res.string.schedule_title)
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
      if (state.busy) CircularProgressIndicator()
      if (state.failed) {
        Text(
          stringResource(Res.string.schedule_could_not_load),
          color = MaterialTheme.colorScheme.error,
        )
      }
      state.schedule?.let { schedule ->
        schedule.items.forEach { Item(it) }
        Doses(Res.string.schedule_reported, schedule.reported)
        Doses(Res.string.schedule_unmapped, schedule.unmapped)
        Doses(Res.string.schedule_unreadable, schedule.unreadable)
      }
      if (state.referralChanged) {
        Text(
          stringResource(Res.string.schedule_referral_changed),
          color = MaterialTheme.colorScheme.error,
        )
      }
      state.booked?.let {
        Text(
          stringResource(Res.string.schedule_booked, it.date.toString(), it.doses),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      Button(
        onClick = viewModel::book,
        enabled = !state.busy && state.booked == null && state.schedule != null,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(Res.string.schedule_book))
      }
    }
  }
}

@Composable
private fun Item(item: ScheduleItem) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(item.title, style = MaterialTheme.typography.labelLarge)
      when (val standing = item.standing) {
        Standing.Complete -> Text(stringResource(Res.string.schedule_complete))
        is Standing.Due -> Text(stringResource(Res.string.schedule_due, standing.date.toString()))
        is Standing.NotYet ->
          Text(stringResource(Res.string.schedule_not_yet, standing.date.toString()))
        is Standing.CouldNotEvaluate ->
          Text(
            stringResource(Res.string.schedule_could_not),
            color = MaterialTheme.colorScheme.error,
          )
      }
    }
  }
}

@Composable
private fun Doses(title: StringResource, doses: List<ScheduleDose>) {
  if (doses.isEmpty()) return
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
      doses.forEach { Text(it.label) }
    }
  }
}
