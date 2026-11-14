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
import dev.ohs.trustdemo.onesa.data.sharing.FamilyDose
import dev.ohs.trustdemo.onesa.data.sharing.FamilyMember
import dev.ohs.trustdemo.onesa.data.sharing.FamilyRecords
import dev.ohs.trustdemo.onesa.data.sharing.Person
import dev.ohs.trustdemo.onesa.data.sharing.ShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * S6's household: who the signed-in family member can share for, each one's relation, and the doses
 * of the [selected] member, a child first.
 */
internal class WalletViewModel(private val service: ShareService, selfId: () -> String?) :
  ViewModel() {
  private val _members = MutableStateFlow<List<Person>?>(null)
  val members: StateFlow<List<Person>?> = _members.asStateFlow()
  private val _family = MutableStateFlow<List<FamilyMember>>(emptyList())
  val family: StateFlow<List<FamilyMember>> = _family.asStateFlow()
  private val _selected = MutableStateFlow<String?>(null)
  val selected: StateFlow<String?> = _selected.asStateFlow()
  private val _doses = MutableStateFlow<List<FamilyDose>>(emptyList())
  val doses: StateFlow<List<FamilyDose>> = _doses.asStateFlow()

  init {
    viewModelScope.launch { service.purgeExpired() }
    viewModelScope.launch {
      service.observeHousehold(selfId()).collect { people ->
        val family = service.family(people)
        _family.value = family
        _members.value = people
        val shown =
          _selected.value?.takeIf { id -> people.any { it.id == id } }
            ?: family.firstOrNull { it.relation == FamilyRecords.CHILD }?.id
            ?: people.firstOrNull()?.id
        shown?.let { select(it) }
      }
    }
  }

  fun select(patientId: String) {
    _selected.value = patientId
    viewModelScope.launch { _doses.value = service.doses(patientId) }
  }
}
