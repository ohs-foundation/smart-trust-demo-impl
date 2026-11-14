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
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.data.verification.SentReferral
import dev.ohs.trustdemo.onesa.feature.home.rememberPlace
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.sent_empty
import dev.ohs.trustdemo.onesa.generated.resources.sent_status_completed
import dev.ohs.trustdemo.onesa.generated.resources.sent_status_open
import dev.ohs.trustdemo.onesa.generated.resources.sent_status_withdrawn
import dev.ohs.trustdemo.onesa.generated.resources.sent_title
import dev.ohs.trustdemo.onesa.generated.resources.sent_title_place
import dev.ohs.trustdemo.onesa.generated.resources.sent_to
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The referrals the signed-in CHW sent, from the synced ServiceRequests. */
class SentReferralsViewModel(inbox: ReferralInbox, practitionerId: () -> String?) : ViewModel() {
  val referrals: StateFlow<List<SentReferral>?> =
    inbox.observeSent(practitionerId()).stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/** The CHW's referrals tab. */
@Composable
fun SentReferralsScreen() {
  val viewModel: SentReferralsViewModel = koinViewModel()
  val referrals by viewModel.referrals.collectAsStateWithLifecycle()
  SentReferralsContent(referrals.orEmpty(), rememberPlace())
}

@Composable
fun SentReferralsContent(referrals: List<SentReferral>, place: String?) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SectionCard(
      title =
        place?.let { stringResource(Res.string.sent_title_place, it) }
          ?: stringResource(Res.string.sent_title),
      summary = Status(referrals.size.toString(), Tone.Info),
    ) {
      if (referrals.isEmpty()) {
        Text(
          stringResource(Res.string.sent_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      referrals.forEach { referral ->
        RecordRow(
          label = referral.patientName,
          code = "ServiceRequest/${referral.id}",
          detail =
            listOfNotNull(
                referral.performer?.let { stringResource(Res.string.sent_to, it) },
                referral.authoredOn?.substringBefore('T'),
                referral.reason,
              )
              .joinToString(" • ")
              .ifEmpty { null },
          status = sentStatus(referral.status),
        )
      }
    }
  }
}

/** A referral's status as its chip: open, completed or withdrawn. */
@Composable
internal fun sentStatus(status: String?): Status? =
  when (status) {
    "active" -> Status(stringResource(Res.string.sent_status_open), Tone.Warning)
    "completed" -> Status(stringResource(Res.string.sent_status_completed), Tone.Success)
    "revoked",
    "entered-in-error" -> Status(stringResource(Res.string.sent_status_withdrawn))
    else -> status?.let { Status(it) }
  }
