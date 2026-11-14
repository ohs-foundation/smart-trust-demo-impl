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
import dev.ohs.trustdemo.onesa.data.sharing.Person
import dev.ohs.trustdemo.onesa.data.sharing.ShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** S6's household: who the signed-in family member can share for. */
internal class WalletViewModel(service: ShareService, selfId: () -> String?) : ViewModel() {
  private val _members = MutableStateFlow<List<Person>?>(null)
  val members: StateFlow<List<Person>?> = _members.asStateFlow()

  init {
    viewModelScope.launch { service.purgeExpired() }
    viewModelScope.launch { service.observeHousehold(selfId()).collect { _members.value = it } }
  }
}
