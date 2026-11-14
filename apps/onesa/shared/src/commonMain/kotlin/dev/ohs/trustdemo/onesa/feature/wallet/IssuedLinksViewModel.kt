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
import dev.ohs.trustdemo.onesa.data.sharing.FamilyMember
import dev.ohs.trustdemo.onesa.data.sharing.IssuedLink
import dev.ohs.trustdemo.onesa.data.sharing.Person
import dev.ohs.trustdemo.onesa.data.sharing.ShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** S7's links: the ones just issued, read back from the encrypted store by Consent id. */
internal class IssuedLinksViewModel(consentIds: List<String>, service: ShareService) : ViewModel() {
  private val _links = MutableStateFlow<List<IssuedLink>?>(null)
  val links: StateFlow<List<IssuedLink>?> = _links.asStateFlow()

  private val _unsaved = MutableStateFlow(false)

  /** True when a link is held in memory only, because this phone could not save it. */
  val unsaved: StateFlow<Boolean> = _unsaved.asStateFlow()

  private val _family = MutableStateFlow<List<FamilyMember>>(emptyList())

  /** Who each link is for: relation and age, for the header. */
  val family: StateFlow<List<FamilyMember>> = _family.asStateFlow()

  init {
    viewModelScope.launch {
      val (found, loose) = service.issued(consentIds)
      _unsaved.value = loose
      _links.value = found
      _family.value = service.family(found.map { Person(it.personId, it.name) })
    }
  }
}
