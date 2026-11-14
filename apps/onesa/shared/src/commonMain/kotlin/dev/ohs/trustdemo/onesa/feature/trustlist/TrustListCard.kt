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
package dev.ohs.trustdemo.onesa.feature.trustlist

import androidx.compose.runtime.Composable
import dev.ohs.trustdemo.onesa.data.hcert.TrustDomain
import dev.ohs.trustdemo.onesa.generated.config.TrustListCardConfig
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.trust_cached_title
import dev.ohs.trustdemo.onesa.generated.resources.trust_detail_bundled
import dev.ohs.trustdemo.onesa.generated.resources.trust_detail_fetched
import dev.ohs.trustdemo.onesa.generated.resources.trust_entry_authority
import dev.ohs.trustdemo.onesa.generated.resources.trust_entry_cards
import dev.ohs.trustdemo.onesa.generated.resources.trust_entry_dsc
import dev.ohs.trustdemo.onesa.generated.resources.trust_entry_kid
import dev.ohs.trustdemo.onesa.generated.resources.trust_entry_links
import dev.ohs.trustdemo.onesa.generated.resources.trust_entry_other
import dev.ohs.trustdemo.onesa.generated.resources.trust_keys
import dev.ohs.trustdemo.onesa.generated.resources.trust_keys_including
import dev.ohs.trustdemo.onesa.generated.resources.trust_last_refresh
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_domains_label
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_not_fetched
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_refresh_failed
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_signer_label
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_stale
import dev.ohs.trustdemo.onesa.generated.resources.trust_local_footnote
import dev.ohs.trustdemo.onesa.generated.resources.trust_local_title
import dev.ohs.trustdemo.onesa.generated.resources.trust_not_fetched_verdict
import dev.ohs.trustdemo.onesa.generated.resources.trust_ready
import dev.ohs.trustdemo.onesa.generated.resources.trust_refresh_at_sync
import dev.ohs.trustdemo.onesa.generated.resources.trust_refresh_never
import dev.ohs.trustdemo.onesa.generated.resources.trust_snapshot
import dev.ohs.trustdemo.onesa.generated.resources.trust_snapshot_bundled
import dev.ohs.trustdemo.onesa.generated.resources.trust_snapshot_fetched
import dev.ohs.trustdemo.onesa.generated.resources.trust_source
import dev.ohs.trustdemo.onesa.generated.resources.trust_stale_verdict
import dev.ohs.trustdemo.onesa.generated.resources.trust_test_only
import dev.ohs.trustdemo.onesa.ui.Field
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.VerificationResultCard
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.displayDate
import dev.ohs.trustdemo.onesa.util.timeAgo
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * A trust list's status, boxed as ready, stale or not fetched: where it comes from, the snapshot it
 * holds, when it was last refreshed and how many document signing keys it has.
 */
@Composable
fun TrustListCard(list: TrustListState, config: TrustListCardConfig = TrustListCardConfig()) {
  val fetched = list.fetchedAtEpochSeconds
  val (verdict, tone) =
    when {
      fetched == null -> stringResource(Res.string.trust_not_fetched_verdict) to Tone.Warning
      list.stale -> stringResource(Res.string.trust_stale_verdict) to Tone.Warning
      else -> stringResource(Res.string.trust_ready) to Tone.Success
    }
  VerificationResultCard(
    title = stringResource(Res.string.trust_cached_title, list.name),
    tone = tone,
    verdict = verdict,
    fields =
      listOfNotNull(
        list.source?.let {
          Field(stringResource(Res.string.trust_source), it.substringAfter("://"), mono = true)
        },
        Field(
          stringResource(Res.string.trust_snapshot),
          when {
            fetched == null -> stringResource(Res.string.trust_list_not_fetched)
            list.bundled -> stringResource(Res.string.trust_snapshot_bundled, day(fetched))
            else -> stringResource(Res.string.trust_snapshot_fetched, list.fetchedAt.orEmpty())
          },
        ),
        Field(
          stringResource(Res.string.trust_last_refresh),
          when {
            list.bundled -> stringResource(Res.string.trust_refresh_never)
            fetched == null -> stringResource(Res.string.trust_list_not_fetched)
            else ->
              stringResource(
                Res.string.trust_refresh_at_sync,
                timeAgo(fetched, Clock.System.now().epochSeconds),
              )
          },
        ),
        Field(
          stringResource(Res.string.trust_keys),
          list.namedKeys?.let {
            stringResource(Res.string.trust_keys_including, list.signingKeys, it)
          } ?: list.signingKeys.toString(),
        ),
        list.refreshFailedAt?.let {
          Field(
            stringResource(Res.string.trust_last_refresh),
            stringResource(Res.string.trust_list_refresh_failed, it, list.refreshFailure.orEmpty()),
          )
        },
        list.signer
          ?.takeIf { config.showSigner != false }
          ?.let { Field(stringResource(Res.string.trust_list_signer_label), it, mono = true) },
        list.domains
          ?.takeIf { config.showDomains != false }
          ?.let { Field(stringResource(Res.string.trust_list_domains_label), it) },
      ),
    detail =
      stringResource(
        if (list.bundled) Res.string.trust_detail_bundled else Res.string.trust_detail_fetched
      ),
  )
}

/** A fetched list's keys, one row each, with when the list was fetched as the footnote. */
@Composable
fun TrustKeysCard(list: TrustListState) {
  SectionCard(
    title =
      if (list.keys.isNotEmpty() && list.keys.all { it.testOnly })
        stringResource(Res.string.trust_local_title)
      else list.name,
    footnote =
      listOfNotNull(
          stringResource(
            Res.string.trust_local_footnote,
            list.name,
            list.fetchedAtEpochSeconds?.let { timeAgo(it, Clock.System.now().epochSeconds) }
              ?: stringResource(Res.string.trust_list_not_fetched),
          ),
          if (list.stale) stringResource(Res.string.trust_list_stale) else null,
          list.refreshFailedAt?.let {
            stringResource(Res.string.trust_list_refresh_failed, it, list.refreshFailure.orEmpty())
          },
        )
        .joinToString(" "),
  ) {
    list.keys.forEach { key ->
      val who = key.participant.orEmpty()
      RecordRow(
        label =
          stringResource(
            if (key.usage == "DSC") Res.string.trust_entry_dsc
            else Res.string.trust_entry_authority,
            who,
          ),
        detail =
          listOfNotNull(
              key.domain?.let {
                stringResource(
                  when (it) {
                    TrustDomain.Dvc.code -> Res.string.trust_entry_cards
                    TrustDomain.Vhl.code -> Res.string.trust_entry_links
                    else -> Res.string.trust_entry_other
                  },
                  it,
                )
              },
              key.kid?.let { stringResource(Res.string.trust_entry_kid, it) },
            )
            .joinToString(" • "),
        status =
          if (key.testOnly) Status(stringResource(Res.string.trust_test_only), Tone.Warning)
          else null,
      )
    }
  }
}

private fun day(epochSeconds: Long): String =
  displayDate(
    Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault()).date
  )
