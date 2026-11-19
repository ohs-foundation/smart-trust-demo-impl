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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.hcert.TrustedDscInfo
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.data.hcert.TrustDomain
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.repository.displayAge
import dev.ohs.trustdemo.onesa.data.verification.ScanOutcome
import dev.ohs.trustdemo.onesa.feature.home.RoleTopBar
import dev.ohs.trustdemo.onesa.feature.scan.CardCameraPanel
import dev.ohs.trustdemo.onesa.feature.scan.CardFailure
import dev.ohs.trustdemo.onesa.feature.scan.QrScan
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.list_stale
import dev.ohs.trustdemo.onesa.generated.resources.scan_error_camera
import dev.ohs.trustdemo.onesa.generated.resources.scan_paste_label
import dev.ohs.trustdemo.onesa.generated.resources.verify_age
import dev.ohs.trustdemo.onesa.generated.resources.verify_already_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_card_born
import dev.ohs.trustdemo.onesa.generated.resources.verify_card_holder
import dev.ohs.trustdemo.onesa.generated.resources.verify_child_born
import dev.ohs.trustdemo.onesa.generated.resources.verify_child_name
import dev.ohs.trustdemo.onesa.generated.resources.verify_confirm
import dev.ohs.trustdemo.onesa.generated.resources.verify_confirm_anyway
import dev.ohs.trustdemo.onesa.generated.resources.verify_could_not_save
import dev.ohs.trustdemo.onesa.generated.resources.verify_decline
import dev.ohs.trustdemo.onesa.generated.resources.verify_decoded
import dev.ohs.trustdemo.onesa.generated.resources.verify_diagnostic_code
import dev.ohs.trustdemo.onesa.generated.resources.verify_dose
import dev.ohs.trustdemo.onesa.generated.resources.verify_dose_code
import dev.ohs.trustdemo.onesa.generated.resources.verify_elsewhere_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_fail
import dev.ohs.trustdemo.onesa.generated.resources.verify_fail_caption
import dev.ohs.trustdemo.onesa.generated.resources.verify_fail_stays
import dev.ohs.trustdemo.onesa.generated.resources.verify_holder_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_imported_detail
import dev.ohs.trustdemo.onesa.generated.resources.verify_imported_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_incoming
import dev.ohs.trustdemo.onesa.generated.resources.verify_keep_reported
import dev.ohs.trustdemo.onesa.generated.resources.verify_kid
import dev.ohs.trustdemo.onesa.generated.resources.verify_list_fetched_label
import dev.ohs.trustdemo.onesa.generated.resources.verify_list_stale
import dev.ohs.trustdemo.onesa.generated.resources.verify_name_differs
import dev.ohs.trustdemo.onesa.generated.resources.verify_name_differs_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_name_reason_hint
import dev.ohs.trustdemo.onesa.generated.resources.verify_name_reason_label
import dev.ohs.trustdemo.onesa.generated.resources.verify_none_pending
import dev.ohs.trustdemo.onesa.generated.resources.verify_nothing_imported
import dev.ohs.trustdemo.onesa.generated.resources.verify_origin
import dev.ohs.trustdemo.onesa.generated.resources.verify_pass
import dev.ohs.trustdemo.onesa.generated.resources.verify_pass_detail
import dev.ohs.trustdemo.onesa.generated.resources.verify_pass_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_pending_count
import dev.ohs.trustdemo.onesa.generated.resources.verify_primary_source_false
import dev.ohs.trustdemo.onesa.generated.resources.verify_primary_source_true
import dev.ohs.trustdemo.onesa.generated.resources.verify_proceed
import dev.ohs.trustdemo.onesa.generated.resources.verify_reason
import dev.ohs.trustdemo.onesa.generated.resources.verify_referred_by
import dev.ohs.trustdemo.onesa.generated.resources.verify_refused
import dev.ohs.trustdemo.onesa.generated.resources.verify_replaced
import dev.ohs.trustdemo.onesa.generated.resources.verify_scan_again
import dev.ohs.trustdemo.onesa.generated.resources.verify_service_request
import dev.ohs.trustdemo.onesa.generated.resources.verify_status_gdhcn
import dev.ohs.trustdemo.onesa.generated.resources.verify_status_local
import dev.ohs.trustdemo.onesa.generated.resources.verify_trust_authority
import dev.ohs.trustdemo.onesa.generated.resources.verify_trust_resolution
import dev.ohs.trustdemo.onesa.generated.resources.verify_unverified_title
import dev.ohs.trustdemo.onesa.generated.resources.verify_use_text
import dev.ohs.trustdemo.onesa.generated.resources.verify_verified_count
import dev.ohs.trustdemo.onesa.generated.resources.verify_verified_title
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.ChildAvatar
import dev.ohs.trustdemo.onesa.ui.Field
import dev.ohs.trustdemo.onesa.ui.FieldGrid
import dev.ohs.trustdemo.onesa.ui.NoteLine
import dev.ohs.trustdemo.onesa.ui.OnesaButton
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaTextField
import dev.ohs.trustdemo.onesa.ui.PatientBanner
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.VerificationResultCard
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.displayTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the verify screens' buttons and camera do. */
class VerifyActions(
  val onScan: (QrScan) -> Unit,
  val onText: (String) -> Unit,
  val confirm: () -> Unit,
  val reason: (String) -> Unit,
  val decline: () -> Unit,
  val proceed: () -> Unit,
  val scanAgain: () -> Unit,
)

/**
 * S4: verifies each of a referred child's cards against the trust list and imports its dose; a card
 * that fails shows why (1-12).
 */
@Composable
fun VerifyCardsScreen(
  referralId: String,
  role: AppRole,
  onBack: () -> Unit,
  onOpenSchedule: () -> Unit,
) {
  val viewModel = koinViewModel<VerifyCardsViewModel>(key = referralId) { parametersOf(referralId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var dismissed by rememberSaveable { mutableIntStateOf(0) }
  var cameraFailed by remember { mutableStateOf(false) }
  val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
  val actions =
    VerifyActions(
      onScan = { scan ->
        when (scan) {
          is QrScan.Text -> {
            cameraFailed = false
            viewModel.onText(scan.value)
          }
          is QrScan.Failed -> cameraFailed = true
          QrScan.Cancelled -> Unit
        }
      },
      onText = viewModel::onText,
      confirm = viewModel::confirm,
      reason = viewModel::reason,
      decline = viewModel::decline,
      proceed = onOpenSchedule,
      scanAgain = { dismissed = state.results.size },
    )
  VerifyCardsContent(
    state = state,
    today = today,
    topBar = { RoleTopBar(role, onBack) },
    showOutcome = state.results.size > dismissed,
    cameraFailed = cameraFailed,
    actions = actions,
  )
}

/** S4, or 1-12 when the last card shown was refused: the referral, the doses and the scanner. */
@Composable
fun VerifyCardsContent(
  state: VerifyCardsUiState,
  today: LocalDate,
  topBar: @Composable () -> Unit,
  showOutcome: Boolean,
  actions: VerifyActions,
  cameraFailed: Boolean = false,
) {
  val pending = state.pending
  val outcome = state.results.lastOrNull()?.takeIf { showOutcome && pending == null }
  val refused =
    outcome is ScanOutcome.Failed ||
      outcome is ScanOutcome.HolderMismatch ||
      outcome is ScanOutcome.ImportedForAnotherChild
  val childName = state.child?.name ?: state.referral?.patientName.orEmpty()
  val signer = pending?.card?.hcert?.dsc ?: (outcome as? ScanOutcome.Imported)?.signer
  val failure = (outcome as? ScanOutcome.Failed)?.let { CardFailure.of(it.reason) }
  var pasted by remember { mutableStateOf("") }

  OnesaScaffold(
    topBar = topBar,
    banner = {
      PatientBanner(
        name = childName,
        chips =
          listOfNotNull(
            displayAge(state.child?.birthDate, today)?.let {
              Status(stringResource(Res.string.verify_age, it), Tone.Info)
            },
            state.referral?.requester?.let {
              Status(stringResource(Res.string.verify_referred_by, it), Tone.Warning)
            },
          ),
        avatar = OnesaIcons.Baby,
        avatarColor = ChildAvatar,
      )
    },
    sideWide = !refused,
    side = {
      CardCameraPanel(
        onResult = actions.onScan,
        frame = state.scanned,
        badge =
          state.scanned?.let {
            if (failure != null) Status(stringResource(failure.badge), Tone.Danger)
            else
              Status(
                stringResource(Res.string.verify_decoded, it.take(10)),
                if (refused) Tone.Warning else Tone.Success,
              )
          },
      )
      if (cameraFailed) {
        Text(
          stringResource(Res.string.scan_error_camera),
          style = MaterialTheme.typography.bodySmall,
          color = OnesaColors.Crimson,
        )
      }
      when {
        pending != null -> {
          VerificationResultCard(
            title = stringResource(Res.string.verify_pass_title),
            tone = Tone.Success,
            verdict = stringResource(Res.string.verify_pass),
            fields =
              signerFields(pending.card.hcert.dsc) +
                listOf(
                  Field(
                    stringResource(Res.string.verify_card_holder),
                    "${pending.holderName}, ${pending.holderBirthDate}",
                  )
                ) +
                listOfNotNull(
                  Field(stringResource(Res.string.verify_child_name), pending.childName).takeIf {
                    pending.nameDiffers
                  }
                ) +
                listOfNotNull(
                  pending.list.fetchedAtEpochSeconds?.let {
                    Field(stringResource(Res.string.verify_list_fetched_label), displayTime(it))
                  }
                ),
            detail =
              stringResource(Res.string.verify_pass_detail, day(pending.card.hcert.dsc.notAfter)),
          )
          if (pending.nameDiffers) {
            AlertCard(
              stringResource(Res.string.verify_name_differs_title),
              stringResource(Res.string.verify_name_differs, pending.holderName, pending.childName),
              Tone.Warning,
            )
            SectionCard {
              OnesaTextField(
                label = stringResource(Res.string.verify_name_reason_label),
                value = state.overrideReason,
                onValueChange = actions.reason,
                placeholder = stringResource(Res.string.verify_name_reason_hint),
                minLines = 2,
              )
            }
          }
          if (pending.list.stale) {
            AlertCard(null, stringResource(Res.string.verify_list_stale), Tone.Warning)
          }
        }
        outcome != null -> OutcomeCard(outcome)
      }
      SectionCard {
        OnesaTextField(
          label = stringResource(Res.string.scan_paste_label),
          value = pasted,
          onValueChange = { pasted = it },
          placeholder = "HC1:…",
          mono = true,
          minLines = 2,
        )
        OnesaButton(
          Action(
            stringResource(Res.string.verify_use_text),
            onClick = {
              actions.onText(pasted)
              pasted = ""
            },
            primary = false,
            enabled = pasted.isNotBlank() && !state.busy && pending == null,
          ),
          Modifier.fillMaxWidth(),
        )
      }
    },
    actions = {
      val busy = state.busy
      PrimaryActionBar(
        actions =
          when {
            pending != null ->
              listOf(
                Action(
                  stringResource(Res.string.verify_decline),
                  onClick = actions.decline,
                  primary = false,
                  enabled = !busy,
                ),
                Action(
                  if (pending.nameDiffers) stringResource(Res.string.verify_confirm_anyway)
                  else stringResource(Res.string.verify_confirm, pending.childName),
                  onClick = actions.confirm,
                  busy = busy,
                  enabled = state.canConfirm,
                ),
              )
            refused ->
              listOf(
                Action(
                  stringResource(Res.string.verify_scan_again),
                  onClick = actions.scanAgain,
                  primary = false,
                ),
                Action(
                  stringResource(Res.string.verify_keep_reported),
                  onClick = actions.proceed,
                  enabled = !busy && state.referral != null,
                ),
              )
            else ->
              listOf(
                Action(
                  stringResource(Res.string.verify_proceed),
                  onClick = actions.proceed,
                  busy = busy,
                  enabled = !busy && state.referral != null,
                )
              )
          },
        caption =
          signer?.let { statusCaption(it) }
            ?: failure?.let {
              stringResource(Res.string.verify_fail_caption, stringResource(it.status))
            },
      )
    },
  ) {
    if (!refused) {
      state.referral?.let { referral ->
        SectionCard(title = stringResource(Res.string.verify_incoming), icon = OnesaIcons.File) {
          FieldGrid(
            listOfNotNull(
              Field(
                stringResource(Res.string.verify_service_request),
                "#${referral.id}",
                mono = true,
              ),
              referral.reason?.let { Field(stringResource(Res.string.verify_reason), it) },
            )
          )
        }
      }
    }
    SectionCard(
      title = stringResource(Res.string.verify_unverified_title),
      summary =
        Status(
          stringResource(Res.string.verify_pending_count, state.reported.size),
          if (state.reported.isEmpty()) Tone.Success else Tone.Warning,
        ),
    ) {
      if (state.reported.isEmpty()) {
        Text(
          stringResource(Res.string.verify_none_pending),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      state.reported.forEach { dose ->
        DoseRow(
          product = dose.product,
          lot = dose.lot,
          origin = dose.origin,
          status = Status(stringResource(Res.string.verify_primary_source_false), Tone.Warning),
        )
      }
    }
    val verified = state.doses.filter { it.verified }
    if (verified.isNotEmpty()) {
      SectionCard(
        title = stringResource(Res.string.verify_verified_title),
        summary =
          Status(stringResource(Res.string.verify_verified_count, verified.size), Tone.Success),
      ) {
        verified.forEach { dose ->
          DoseRow(
            product = dose.product,
            lot = dose.lot,
            origin = dose.origin,
            status = Status(stringResource(Res.string.verify_primary_source_true), Tone.Success),
          )
        }
      }
    }
    Messages(state)
  }
}

@Composable
private fun DoseRow(product: String, lot: String?, origin: String?, status: Status) {
  RecordRow(
    label = NewbornRegistration.PRODUCTS[product] ?: product,
    icon = OnesaIcons.Syringe,
    code = stringResource(Res.string.verify_dose_code, product, lot ?: "-"),
    detail =
      origin?.let { stringResource(Res.string.verify_origin, NewbornRegistration.countryName(it)) },
    status = status,
  )
}

@Composable
private fun Messages(state: VerifyCardsUiState) {
  if (state.couldNotSave) {
    AlertCard(null, stringResource(Res.string.verify_could_not_save), Tone.Danger)
  }
}

@Composable
private fun OutcomeCard(outcome: ScanOutcome) {
  val nothing = stringResource(Res.string.verify_nothing_imported)
  when (outcome) {
    is ScanOutcome.Imported -> {
      VerificationResultCard(
        title = stringResource(Res.string.verify_imported_title),
        tone = Tone.Success,
        verdict = stringResource(Res.string.verify_pass),
        fields =
          listOf(Field(stringResource(Res.string.verify_dose), outcome.dose)) +
            outcome.signer?.let { signerFields(it) }.orEmpty(),
        detail =
          listOfNotNull(
              stringResource(Res.string.verify_imported_detail),
              if (outcome.replacedReported) stringResource(Res.string.verify_replaced) else null,
            )
            .joinToString(" "),
      )
      if (outcome.listStale) NoteLine(stringResource(Res.string.list_stale))
    }
    is ScanOutcome.AlreadyImported ->
      VerificationResultCard(
        title = stringResource(Res.string.verify_already_title),
        tone = Tone.Success,
        verdict = stringResource(Res.string.verify_pass),
        fields = listOf(Field(stringResource(Res.string.verify_dose), outcome.dose)),
        detail = nothing,
      )
    is ScanOutcome.Failed -> {
      val failure = CardFailure.of(outcome.reason)
      VerificationResultCard(
        title = stringResource(failure.title),
        tone = Tone.Danger,
        verdict = stringResource(Res.string.verify_fail),
        fields =
          listOf(
            Field(
              stringResource(Res.string.verify_trust_resolution),
              stringResource(failure.resolution),
            ),
            Field(stringResource(Res.string.verify_diagnostic_code), failure.code, mono = true),
          ),
        detail = "${stringResource(failure.detail)} ${stringResource(Res.string.verify_fail_stays)}",
      )
    }
    is ScanOutcome.HolderMismatch ->
      VerificationResultCard(
        title = stringResource(Res.string.verify_holder_title),
        tone = Tone.Danger,
        verdict = stringResource(Res.string.verify_refused),
        fields =
          listOf(
            Field(stringResource(Res.string.verify_card_born), outcome.cardBirthDate),
            Field(stringResource(Res.string.verify_child_born), outcome.childBirthDate ?: "-"),
          ),
        detail = nothing,
      )
    is ScanOutcome.ImportedForAnotherChild ->
      VerificationResultCard(
        title = stringResource(Res.string.verify_elsewhere_title),
        tone = Tone.Danger,
        verdict = stringResource(Res.string.verify_refused),
        fields = listOf(Field(stringResource(Res.string.verify_dose), outcome.dose)),
        detail = nothing,
      )
  }
}

/** Who signed the card: the participant and trust domain, and the key. */
@Composable
private fun signerFields(dsc: TrustedDscInfo): List<Field> =
  listOf(
    Field(
      stringResource(Res.string.verify_trust_authority),
      listOfNotNull(
          (dsc.participant ?: dsc.country)?.let(NewbornRegistration::countryName),
          dsc.domain,
        )
        .joinToString(" • "),
    ),
    Field(stringResource(Res.string.verify_kid), dsc.kid, mono = true),
  )

/** Which list vouched for the signer, as ScanTransactions records it in the Provenance. */
@Composable
private fun statusCaption(dsc: TrustedDscInfo): String =
  if (dsc.domain == TrustDomain.Dvc.code) {
    stringResource(Res.string.verify_status_local, TrustDomain.Dvc.code)
  } else stringResource(Res.string.verify_status_gdhcn)

private fun day(epochSeconds: Long): String =
  Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.UTC).date.toString()
