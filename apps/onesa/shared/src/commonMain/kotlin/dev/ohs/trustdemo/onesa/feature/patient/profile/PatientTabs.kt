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
package dev.ohs.trustdemo.onesa.feature.patient.profile

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ohs.trustdemo.onesa.data.schedule.DisplayOnlyRow
import dev.ohs.trustdemo.onesa.data.schedule.DisplayOnlyRows
import dev.ohs.trustdemo.onesa.data.schedule.Schedule
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleService
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.data.verification.SentReferral
import dev.ohs.trustdemo.onesa.feature.group.profile.GroupProfileViewModel
import dev.ohs.trustdemo.onesa.feature.group.profile.HouseholdCard
import dev.ohs.trustdemo.onesa.feature.referral.ScheduleCard
import dev.ohs.trustdemo.onesa.feature.referral.sentStatus
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.profile_no_household
import dev.ohs.trustdemo.onesa.generated.resources.profile_no_referrals
import dev.ohs.trustdemo.onesa.generated.resources.profile_referrals_title
import dev.ohs.trustdemo.onesa.generated.resources.schedule_could_not_load
import dev.ohs.trustdemo.onesa.generated.resources.sent_to
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The national schedule evaluated for one patient; [failed] when it could not be. */
class PatientScheduleViewModel(patientId: String, service: ScheduleService) : ViewModel() {
  private val _schedule = MutableStateFlow<Schedule?>(null)
  val schedule: StateFlow<Schedule?> = _schedule.asStateFlow()
  private val _failed = MutableStateFlow(false)
  val failed: StateFlow<Boolean> = _failed.asStateFlow()

  init {
    viewModelScope.launch {
      try {
        _schedule.value = service.evaluate(patientId)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        _failed.value = true
      }
    }
  }
}

/** The referrals about one patient, newest first; null until read. */
class PatientReferralsViewModel(patientId: String, inbox: ReferralInbox) : ViewModel() {
  private val _referrals = MutableStateFlow<List<SentReferral>?>(null)
  val referrals: StateFlow<List<SentReferral>?> = _referrals.asStateFlow()

  init {
    viewModelScope.launch { _referrals.value = inbox.about(patientId) }
  }
}

/** The profile's Schedule tab: the national schedule as evaluated for the patient. */
@Composable
fun PatientScheduleTab(patientId: String) {
  val viewModel =
    koinViewModel<PatientScheduleViewModel>(key = "schedule:$patientId") { parametersOf(patientId) }
  val schedule by viewModel.schedule.collectAsStateWithLifecycle()
  val failed by viewModel.failed.collectAsStateWithLifecycle()
  val upcoming by produceState(emptyList<DisplayOnlyRow>()) { value = DisplayOnlyRows.load() }
  when {
    failed -> AlertCard(null, stringResource(Res.string.schedule_could_not_load), Tone.Danger)
    else -> schedule?.let { ScheduleCard(it, upcoming) } ?: CircularProgressIndicator()
  }
}

/** The profile's Referrals tab: each referral about the patient, where to and how it stands. */
@Composable
fun PatientReferralsTab(patientId: String) {
  val viewModel =
    koinViewModel<PatientReferralsViewModel>(key = "referrals:$patientId") {
      parametersOf(patientId)
    }
  val referrals by viewModel.referrals.collectAsStateWithLifecycle()
  val loaded = referrals
  if (loaded == null) {
    CircularProgressIndicator()
    return
  }
  SectionCard(
    title = stringResource(Res.string.profile_referrals_title),
    summary = Status(loaded.size.toString(), Tone.Info),
  ) {
    if (loaded.isEmpty()) {
      Text(
        stringResource(Res.string.profile_no_referrals),
        style = MaterialTheme.typography.bodyMedium,
        color = OnesaColors.Muted,
      )
    }
    loaded.forEach { referral ->
      RecordRow(
        label = referral.performer?.let { stringResource(Res.string.sent_to, it) } ?: referral.id,
        code = "ServiceRequest/${referral.id}",
        detail =
          listOfNotNull(referral.authoredOn?.substringBefore('T'), referral.reason)
            .joinToString(" • ")
            .ifEmpty { null },
        status = sentStatus(referral.status),
      )
    }
  }
}

/** The profile's Household tab: the household the patient belongs to, and its members. */
@Composable
fun PatientHouseholdTab(groupId: String?, onOpenPatient: (String) -> Unit) {
  if (groupId == null) {
    Text(
      stringResource(Res.string.profile_no_household),
      style = MaterialTheme.typography.bodyMedium,
      color = OnesaColors.Muted,
    )
    return
  }
  val viewModel = koinViewModel<GroupProfileViewModel>(key = groupId) { parametersOf(groupId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  state?.let { HouseholdCard(it, onOpenPatient) } ?: CircularProgressIndicator()
}
