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
import dev.ohs.trustdemo.onesa.data.verification.DoseMatchService
import dev.ohs.trustdemo.onesa.data.verification.EpiDose
import dev.ohs.trustdemo.onesa.data.verification.MatchOutcome
import dev.ohs.trustdemo.onesa.data.verification.PendingImport
import dev.ohs.trustdemo.onesa.data.verification.Referral
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.data.verification.ScanCheck
import dev.ohs.trustdemo.onesa.data.verification.ScanOutcome
import dev.ohs.trustdemo.onesa.data.verification.UnmatchedDose
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
  val unmatched: List<UnmatchedDose> = emptyList(),
  val choices: List<EpiDose> = emptyList(),
  val matchChanged: Boolean = false,
  val matchedTo: List<EpiDose> = emptyList(),
)

/** Scans each card of a referral's child through [VerificationService]. */
class VerifyCardsViewModel(
  private val referralId: String,
  private val inbox: ReferralInbox,
  private val service: VerificationService,
  private val matching: DoseMatchService,
) : ViewModel() {
  private val _uiState = MutableStateFlow(VerifyCardsUiState())
  val uiState: StateFlow<VerifyCardsUiState> = _uiState.asStateFlow()

  private var lastText: String? = null
  private val leftUnmapped = mutableSetOf<String>()

  init {
    viewModelScope.launch {
      _uiState.update { it.copy(referral = inbox.get(referralId)) }
      loadUnmatched()
    }
  }

  /**
   * The nurse says the dose counts as [choice]: DT.02 rule 3 writes the map, the dose and her note.
   */
  fun match(dose: UnmatchedDose, choice: EpiDose) {
    if (_uiState.value.busy) return
    _uiState.update { it.copy(busy = true, couldNotSave = false, matchChanged = false) }
    viewModelScope.launch {
      val outcome = attempt { matching.confirm(dose, choice) }
      _uiState.update {
        it.copy(
          busy = false,
          couldNotSave = outcome == null,
          matchChanged = outcome is MatchOutcome.Changed,
          matchedTo =
            if (outcome is MatchOutcome.Matched) it.matchedTo + outcome.dose else it.matchedTo,
        )
      }
      loadUnmatched()
    }
  }

  /**
   * None of the schedule doses fits (DT.02 rule 4): nothing is written and the dose stays unmapped.
   */
  fun leaveUnmapped(dose: UnmatchedDose) {
    leftUnmapped += dose.id
    _uiState.update { it.copy(unmatched = it.unmatched.filter { other -> other.id != dose.id }) }
  }

  private suspend fun loadUnmatched() {
    val referral = _uiState.value.referral ?: return
    val doses = attempt { matching.unmatched(referral.patientId) } ?: return
    val choices = attempt { matching.choices() } ?: return
    _uiState.update {
      it.copy(unmatched = doses.filter { dose -> dose.id !in leftUnmapped }, choices = choices)
    }
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
    viewModelScope.launch { loadUnmatched() }
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
