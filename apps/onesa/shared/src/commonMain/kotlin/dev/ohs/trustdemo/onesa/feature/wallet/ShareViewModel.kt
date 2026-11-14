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
import dev.ohs.trustdemo.onesa.auth.SessionStore
import dev.ohs.trustdemo.onesa.data.sharing.ConsentTerm
import dev.ohs.trustdemo.onesa.data.sharing.ConsentTerms
import dev.ohs.trustdemo.onesa.data.sharing.FamilyMember
import dev.ohs.trustdemo.onesa.data.sharing.Person
import dev.ohs.trustdemo.onesa.data.sharing.ShareEntry
import dev.ohs.trustdemo.onesa.data.sharing.ShareOutcome
import dev.ohs.trustdemo.onesa.data.sharing.ShareProblem
import dev.ohs.trustdemo.onesa.data.sharing.ShareService
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

private const val DEFAULT_DAYS = 14

private fun utcToday(): LocalDate = Clock.System.todayIn(TimeZone.UTC)

/** Everything the share and consent screen shows and what the holder has entered so far. */
internal data class ShareUiState(
  val members: List<Person> = emptyList(),
  val family: List<FamilyMember> = emptyList(),
  val selected: Set<String> = emptySet(),
  val terms: List<ConsentTerm> = emptyList(),
  val today: LocalDate,
  val end: LocalDate,
  val passcode: String = "",
  val again: String = "",
  val agreed: Boolean = false,
  val sharing: Boolean = false,
  val refusal: String? = null,
  val issued: List<String>? = null,
) {
  val entry: ShareEntry
    get() = ShareEntry(members.map { it.id }.filter { it in selected }, end, passcode, again)

  val problem: ShareProblem?
    get() = entry.problem(today)

  val canShare: Boolean
    get() = problem == null && agreed && !sharing

  override fun toString(): String = "ShareUiState(selected=$selected, end=$end)"
}

/** S6's share and consent: who, the terms, the last day, the passcode, and the call. */
internal class ShareViewModel(private val service: ShareService, store: SessionStore) :
  ViewModel() {
  private val today = utcToday()
  private var pending: Pair<ShareEntry, String>? = null
  private val _state =
    MutableStateFlow(ShareUiState(today = today, end = today.plus(DatePeriod(days = DEFAULT_DAYS))))
  val state: StateFlow<ShareUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      val members = service.household(store.session.value?.context?.patientId)
      _state.update {
        it.copy(
          members = members,
          family = service.family(members),
          selected = members.filter { m -> m.name != null }.map(Person::id).toSet(),
        )
      }
    }
    viewModelScope.launch { _state.update { it.copy(terms = ConsentTerms.load()) } }
  }

  fun toggle(personId: String) =
    _state.update {
      it.copy(
        selected = if (personId in it.selected) it.selected - personId else it.selected + personId
      )
    }

  fun end(date: LocalDate) = _state.update { it.copy(end = date) }

  fun passcode(text: String) = _state.update { it.copy(passcode = text) }

  fun again(text: String) = _state.update { it.copy(again = text) }

  fun agree(agreed: Boolean) = _state.update { it.copy(agreed = agreed) }

  @OptIn(ExperimentalUuidApi::class)
  fun share() {
    val today = utcToday()
    if (_state.value.end < today) {
      _state.update { it.copy(today = today, end = today) }
      return
    }
    val current = _state.value.copy(today = today)
    if (!current.canShare) return
    _state.update { it.copy(today = today, sharing = true, refusal = null) }
    val entry = current.entry
    val key = pending?.takeIf { it.first == entry }?.second ?: Uuid.random().toString()
    pending = entry to key
    viewModelScope.launch {
      val next =
        try {
          when (val outcome = service.share(entry, today, key)) {
            is ShareOutcome.Issued -> {
              pending = null
              current.copy(sharing = false, passcode = "", again = "", issued = outcome.consentIds)
            }
            is ShareOutcome.Refused -> {
              pending = null
              current.copy(sharing = false, refusal = outcome.message)
            }
            is ShareOutcome.Unknown -> current.copy(sharing = false, refusal = outcome.message)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          current.copy(sharing = false, refusal = e.message ?: "The share did not go through.")
        }
      _state.value = next
    }
  }
}
