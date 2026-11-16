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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S6's household: who the signed-in family member can share for, each one's relation, and the doses
 * of every member whose summary is [open], a child's at first.
 */
internal class WalletViewModel(private val service: ShareService, selfId: () -> String?) :
  ViewModel() {
  private val _members = MutableStateFlow<List<Person>?>(null)
  val members: StateFlow<List<Person>?> = _members.asStateFlow()
  private val _family = MutableStateFlow<List<FamilyMember>>(emptyList())
  val family: StateFlow<List<FamilyMember>> = _family.asStateFlow()
  private val _open = MutableStateFlow<Set<String>>(emptySet())
  val open: StateFlow<Set<String>> = _open.asStateFlow()
  private val _doses = MutableStateFlow<Map<String, List<FamilyDose>>>(emptyMap())
  val doses: StateFlow<Map<String, List<FamilyDose>>> = _doses.asStateFlow()
  private var toggled = false

  init {
    viewModelScope.launch { service.purgeExpired() }
    viewModelScope.launch {
      service.observeHousehold(selfId()).collect { people ->
        val family = service.family(people)
        _family.value = family
        _members.value = people
        _open.value =
          if (toggled) _open.value intersect people.map(Person::id).toSet()
          else
            setOfNotNull(
              family.firstOrNull { it.relation == FamilyRecords.CHILD }?.id
                ?: people.firstOrNull()?.id
            )
        val loaded = _open.value.associateWith { service.doses(it) }
        _doses.update { it + loaded }
      }
    }
  }

  /** Opens [patientId]'s summary under their name, or closes it when it is open. */
  fun toggle(patientId: String) {
    toggled = true
    if (patientId in _open.value) {
      _open.update { it - patientId }
      return
    }
    _open.update { it + patientId }
    viewModelScope.launch {
      val doses = service.doses(patientId)
      _doses.update { it + (patientId to doses) }
    }
  }
}
