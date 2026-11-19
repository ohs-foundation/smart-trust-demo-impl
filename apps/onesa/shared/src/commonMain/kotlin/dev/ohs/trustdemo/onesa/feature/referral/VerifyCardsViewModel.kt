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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.trustdemo.onesa.data.repository.PatientContext
import dev.ohs.trustdemo.onesa.data.repository.PatientContexts
import dev.ohs.trustdemo.onesa.data.verification.ForeignDose
import dev.ohs.trustdemo.onesa.data.verification.ForeignDoses
import dev.ohs.trustdemo.onesa.data.verification.PendingImport
import dev.ohs.trustdemo.onesa.data.verification.Referral
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.data.verification.ReportedDose
import dev.ohs.trustdemo.onesa.data.verification.ScanCheck
import dev.ohs.trustdemo.onesa.data.verification.ScanOutcome
import dev.ohs.trustdemo.onesa.data.verification.VerificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S4's state: the referral, every scan so far, the card awaiting the nurse and any scan under way,
 * with the child's record as it stands: the doses still [reported], the verified foreign [doses]
 * and the [child]'s household and audits. [scanned] is the last card text read.
 */
data class VerifyCardsUiState(
  val referral: Referral? = null,
  val child: PatientContext? = null,
  val reported: List<ReportedDose> = emptyList(),
  val doses: List<ForeignDose> = emptyList(),
  val scanned: String? = null,
  val results: List<ScanOutcome> = emptyList(),
  val pending: PendingImport? = null,
  val busy: Boolean = false,
  val couldNotSave: Boolean = false,
  val overrideReason: String = "",
) {
  /** A card whose name differs from the child's is confirmed only with the nurse's reason. */
  val canConfirm: Boolean
    get() = pending != null && !busy && (!pending.nameDiffers || overrideReason.isNotBlank())
}

/** Scans each card of a referral's child through [VerificationService]. */
class VerifyCardsViewModel(
  private val referralId: String,
  private val inbox: ReferralInbox,
  private val service: VerificationService,
  private val contexts: PatientContexts,
  private val foreign: ForeignDoses,
) : ViewModel() {
  private val _uiState = MutableStateFlow(VerifyCardsUiState())
  val uiState: StateFlow<VerifyCardsUiState> = _uiState.asStateFlow()

  private var lastText: String? = null

  init {
    viewModelScope.launch {
      _uiState.update { it.copy(referral = inbox.get(referralId)) }
      loadRecords()
    }
  }

  private suspend fun loadRecords() {
    val referral = _uiState.value.referral ?: return
    val child = attempt { contexts.of(referral.patientId) }
    val reported = attempt { inbox.reportedDoses(referral.patientId) }.orEmpty()
    val foreignDoses = attempt { foreign.of(referral.patientId) }.orEmpty()
    _uiState.update { it.copy(child = child, reported = reported, doses = foreignDoses) }
  }

  /**
   * A scan of a card, or a pasted card. The camera repeating a card that just went in is ignored; a
   * card that did not go in is processed again, as the trust list may have changed.
   */
  fun onText(text: String) {
    val state = _uiState.value
    val referral = state.referral
    val card = text.trim()
    if (referral == null || card.isEmpty() || state.busy || state.pending != null) return
    if (card == lastText) return
    lastText = null
    _uiState.update { it.copy(busy = true, couldNotSave = false, scanned = card) }
    viewModelScope.launch {
      val checked = attempt { service.check(referral, card) }
      when (checked) {
        is ScanCheck.Settled -> record(checked.outcome, card)
        is ScanCheck.Pending -> {
          lastText = card
          _uiState.update { it.copy(busy = false, pending = checked.pending, overrideReason = "") }
        }
        null -> _uiState.update { it.copy(busy = false, couldNotSave = true) }
      }
    }
  }

  /** The nurse says the pending card is the child's: only now is anything imported. */
  fun confirm() {
    val state = _uiState.value
    val pending = state.pending ?: return
    if (!state.canConfirm) return
    _uiState.update { it.copy(busy = true, couldNotSave = false) }
    viewModelScope.launch {
      val outcome = attempt { service.import(pending, state.overrideReason) }
      if (outcome == null) _uiState.update { it.copy(busy = false, couldNotSave = true) }
      else record(outcome, pending.hc1)
    }
  }

  fun decline() {
    lastText = null
    _uiState.update { it.copy(pending = null, couldNotSave = false, overrideReason = "") }
  }

  fun reason(text: String) = _uiState.update { it.copy(overrideReason = text) }

  private fun record(outcome: ScanOutcome, card: String) {
    lastText =
      card.takeIf { outcome is ScanOutcome.Imported || outcome is ScanOutcome.AlreadyImported }
    _uiState.update {
      it.copy(busy = false, pending = null, overrideReason = "", results = it.results + outcome)
    }
    viewModelScope.launch { loadRecords() }
  }

  private suspend fun <T> attempt(block: suspend () -> T): T? =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
}
