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
import androidx.compose.ui.Modifier
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.verification.Referral
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.inbox_date
import dev.ohs.trustdemo.onesa.generated.resources.inbox_from
import dev.ohs.trustdemo.onesa.generated.resources.inbox_network
import dev.ohs.trustdemo.onesa.generated.resources.inbox_network_value
import dev.ohs.trustdemo.onesa.generated.resources.inbox_open_count
import dev.ohs.trustdemo.onesa.generated.resources.inbox_snomed
import dev.ohs.trustdemo.onesa.generated.resources.inbox_title
import dev.ohs.trustdemo.onesa.generated.resources.inbox_title_no_place
import dev.ohs.trustdemo.onesa.generated.resources.inbox_today
import dev.ohs.trustdemo.onesa.generated.resources.inbox_urgent
import dev.ohs.trustdemo.onesa.generated.resources.referral_inbox_empty
import dev.ohs.trustdemo.onesa.ui.Field
import dev.ohs.trustdemo.onesa.ui.FieldGrid
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.displayDate
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** Beside the inbox: today's date and how the clinic is connected ([network]). */
@Composable
fun InboxToday(today: LocalDate, network: String) {
  SectionCard(title = stringResource(Res.string.inbox_today)) {
    FieldGrid(
      listOf(
        Field(stringResource(Res.string.inbox_date), displayDate(today)),
        Field(
          stringResource(Res.string.inbox_network),
          stringResource(Res.string.inbox_network_value, network),
        ),
      )
    )
  }
}

/**
 * The clinician's referral inbox: each child referred to this clinic, urgent ones first and marked,
 * with the one [selected] for "Open Referral" outlined when there is more than one.
 */
@Composable
fun ReferralInboxContent(
  referrals: List<Referral>,
  place: String?,
  selected: String?,
  onSelect: (String) -> Unit,
) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SectionCard(
      title =
        place?.let { stringResource(Res.string.inbox_title, it) }
          ?: stringResource(Res.string.inbox_title_no_place),
      summary = Status(stringResource(Res.string.inbox_open_count, referrals.size), Tone.Warning),
    ) {
      if (referrals.isEmpty()) {
        Text(
          stringResource(Res.string.referral_inbox_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      referrals.forEach { referral ->
        RecordRow(
          label = referral.patientName,
          code =
            stringResource(
              Res.string.inbox_snomed,
              "ServiceRequest/${referral.id}",
              ReferralInbox.REFERRAL_CODE,
            ),
          detail =
            listOfNotNull(
                referral.requester?.let { stringResource(Res.string.inbox_from, it) },
                referral.authoredOn?.let { authored ->
                  runCatching { displayDate(LocalDate.parse(authored.take(10))) }.getOrNull()
                },
                referral.purpose,
              )
              .joinToString(" • ")
              .ifEmpty { null },
          status =
            referral.priority?.let {
              if (it == NewbornRegistration.URGENT) {
                Status(stringResource(Res.string.inbox_urgent), Tone.Danger)
              } else Status(it.replaceFirstChar(Char::uppercaseChar))
            },
          selected = referral.id == selected && referrals.size > 1,
          onClick = { onSelect(referral.id) },
        )
      }
    }
  }
}
