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
package dev.ohs.trustdemo.onesa.feature.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.trustdemo.hcert.ReadResult
import dev.ohs.trustdemo.onesa.data.hcert.HcertService
import dev.ohs.trustdemo.onesa.data.registration.ScannedCard
import dev.ohs.trustdemo.onesa.data.registration.ScannedCards
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** S2's state: the cards read so far, and why the last scan was not taken. */
data class ScanCardUiState(
  val cards: List<ScannedCard> = emptyList(),
  val error: ScanError? = null,
)

enum class ScanError {
  NotACard,
  NoDose,
  AlreadyRead,
  Camera,
}

/** Reads cards without verifying them, into the in-memory [ScannedCards] of this visit. */
class ScanCardViewModel(private val hcert: HcertService, private val scannedCards: ScannedCards) :
  ViewModel() {
  private val error = MutableStateFlow<ScanError?>(null)

  val uiState: StateFlow<ScanCardUiState> =
    combine(scannedCards.cards, error, ::ScanCardUiState)
      .stateIn(viewModelScope, SharingStarted.Eagerly, ScanCardUiState())

  init {
    scannedCards.clear()
  }

  fun onScan(scan: QrScan) {
    when (scan) {
      is QrScan.Text -> onText(scan.value)
      is QrScan.Failed -> error.value = ScanError.Camera
      QrScan.Cancelled -> Unit
    }
  }

  fun onText(text: String) {
    viewModelScope.launch {
      val hc1 = text.trim()
      error.value =
        when (val result = hcert.read(hc1)) {
          is ReadResult.Failed -> ScanError.NotACard
          is ReadResult.Decoded ->
            result.hcert.dvc?.let { dvc ->
              if (scannedCards.add(ScannedCard(hc1, dvc))) null else ScanError.AlreadyRead
            } ?: ScanError.NoDose
        }
    }
  }
}
