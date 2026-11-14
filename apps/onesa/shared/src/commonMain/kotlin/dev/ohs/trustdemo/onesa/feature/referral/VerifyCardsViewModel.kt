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
import dev.ohs.trustdemo.onesa.data.verification.Referral
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.data.verification.ScanOutcome
import dev.ohs.trustdemo.onesa.data.verification.VerificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** S4's state: the referral, every scan so far and whether one is under way. */
data class VerifyCardsUiState(
  val referral: Referral? = null,
  val results: List<ScanOutcome> = emptyList(),
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

  /** A scan of a card, or a pasted card; the camera repeating the card just read is ignored. */
  fun onText(text: String) {
    val referral = _uiState.value.referral
    val card = text.trim()
    if (referral == null || card.isEmpty() || _uiState.value.busy || card == lastText) return
    lastText = card
    _uiState.update { it.copy(busy = true, couldNotSave = false) }
    viewModelScope.launch {
      val outcome =
        try {
          service.scan(referral, card)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          lastText = null
          null
        }
      _uiState.update {
        it.copy(
          busy = false,
          couldNotSave = outcome == null,
          results = if (outcome == null) it.results else it.results + outcome,
        )
      }
    }
  }
}
