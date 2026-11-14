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
package dev.ohs.trustdemo.onesa.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.trustdemo.onesa.data.sharing.SharedLink
import dev.ohs.trustdemo.onesa.data.sharing.SharedLinks
import dev.ohs.trustdemo.onesa.data.sharing.WithdrawOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The shared links screen: each share with who looked, and withdrawing one. */
internal class SharedLinksViewModel(private val shared: SharedLinks) : ViewModel() {
  private val _links = MutableStateFlow<List<SharedLink>?>(null)
  val links: StateFlow<List<SharedLink>?> = _links.asStateFlow()

  private val _notice = MutableStateFlow<WithdrawOutcome?>(null)

  /** What the last withdrawal came to. */
  val notice: StateFlow<WithdrawOutcome?> = _notice.asStateFlow()

  init {
    viewModelScope.launch { shared.observe().collect { _links.value = it } }
  }

  fun withdraw(consentId: String) {
    viewModelScope.launch {
      _notice.value = shared.withdraw(consentId)
      _links.value = shared.list()
    }
  }

  fun dismissNotice() {
    _notice.value = null
  }
}
