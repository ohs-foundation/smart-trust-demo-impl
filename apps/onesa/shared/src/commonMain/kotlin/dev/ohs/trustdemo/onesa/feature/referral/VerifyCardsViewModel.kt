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
import dev.ohs.trustdemo.onesa.data.verification.PendingImport
import dev.ohs.trustdemo.onesa.data.verification.Referral
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
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
 * S4's state: the referral, every scan so far, the card awaiting the nurse and any scan under way.
 */
data class VerifyCardsUiState(
  val referral: Referral? = null,
  val results: List<ScanOutcome> = emptyList(),
  val pending: PendingImport? = null,
  val busy: Boolean = false,
  val couldNotSave: Boolean = false,
)

/** Scans each card of a referral's child through [VerificationService]. */
class VerifyCardsViewModel(
  private val referralId: String,
  private val inbox: ReferralInbox,
  private val service: VerificationService,
) : ViewModel() {
  private val _uiState = MutableStateFlow(VerifyCardsUiState())
  val uiState: StateFlow<VerifyCardsUiState> = _uiState.asStateFlow()

  private var lastText: String? = null

  init {
    viewModelScope.launch { _uiState.update { it.copy(referral = inbox.get(referralId)) } }
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
    _uiState.update { it.copy(busy = true, couldNotSave = false) }
    viewModelScope.launch {
      val checked = attempt { service.check(referral, card) }
      when (checked) {
        is ScanCheck.Settled -> record(checked.outcome, card)
        is ScanCheck.Pending -> {
          lastText = card
          _uiState.update { it.copy(busy = false, pending = checked.pending) }
        }
        null -> _uiState.update { it.copy(busy = false, couldNotSave = true) }
      }
    }
  }

  /** The nurse says the pending card is the child's: only now is anything imported. */
  fun confirm() {
    val pending = _uiState.value.pending ?: return
    if (_uiState.value.busy) return
    _uiState.update { it.copy(busy = true, couldNotSave = false) }
    viewModelScope.launch {
      val outcome = attempt { service.import(pending) }
      if (outcome == null) _uiState.update { it.copy(busy = false, couldNotSave = true) }
      else record(outcome, pending.hc1)
    }
  }

  fun decline() {
    lastText = null
    _uiState.update { it.copy(pending = null, couldNotSave = false) }
  }

  private fun record(outcome: ScanOutcome, card: String) {
    lastText =
      card.takeIf { outcome is ScanOutcome.Imported || outcome is ScanOutcome.AlreadyImported }
    _uiState.update { it.copy(busy = false, pending = null, results = it.results + outcome) }
  }

  private suspend fun <T : Any> attempt(block: suspend () -> T): T? =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
}
