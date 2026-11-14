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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.schedule.BookedVisits
import dev.ohs.trustdemo.onesa.data.schedule.ClinicVisit
import dev.ohs.trustdemo.onesa.data.verification.shortDoseName
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.profile_due_day
import dev.ohs.trustdemo.onesa.generated.resources.visits_empty
import dev.ohs.trustdemo.onesa.generated.resources.visits_overdue
import dev.ohs.trustdemo.onesa.generated.resources.visits_title
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.dayMonth
import dev.ohs.trustdemo.onesa.util.displayRange
import kotlin.time.Clock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The visits this clinic has booked, from the synced reminders; null until read. */
class ClinicScheduleViewModel(repository: FhirRepository) : ViewModel() {
  private val visits = BookedVisits(repository)
  val booked: StateFlow<List<ClinicVisit>?> =
    repository.revision
      .map { visits.all() }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** The clinician's Schedule tab. */
@Composable
fun ClinicScheduleScreen(onOpenPatient: (String) -> Unit) {
  val viewModel: ClinicScheduleViewModel = koinViewModel()
  val booked by viewModel.booked.collectAsStateWithLifecycle()
  val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
  ClinicScheduleContent(booked.orEmpty(), today, onOpenPatient)
}

/** Each booked visit: whose, its reminder, the doses it is for and when it falls due. */
@Composable
fun ClinicScheduleContent(
  visits: List<ClinicVisit>,
  today: LocalDate,
  onOpenPatient: (String) -> Unit,
) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SectionCard(
      title = stringResource(Res.string.visits_title),
      summary = Status(visits.size.toString(), Tone.Info),
    ) {
      if (visits.isEmpty()) {
        Text(
          stringResource(Res.string.visits_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      visits.forEach { booked ->
        val visit = booked.visit
        val from = visit.reminderFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val to = visit.reminderTo?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        RecordRow(
          label = booked.patientName,
          code = visit.reminderId?.let { "Task/$it" },
          detail =
            listOfNotNull(
                visit.doses.joinToString { shortDoseName(it.name) }.ifEmpty { null },
                if (from != null && to != null) displayRange(from, to) else null,
              )
              .joinToString(" • "),
          status =
            when {
              to != null && to < today ->
                Status(stringResource(Res.string.visits_overdue), Tone.Danger)
              from != null ->
                Status(stringResource(Res.string.profile_due_day, dayMonth(from)), Tone.Warning)
              else -> null
            },
          onClick = { onOpenPatient(booked.patientId) },
        )
      }
    }
  }
}
