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
import dev.ohs.trustdemo.onesa.data.repository.PatientContext
import dev.ohs.trustdemo.onesa.data.repository.PatientContexts
import dev.ohs.trustdemo.onesa.data.schedule.BookingOutcome
import dev.ohs.trustdemo.onesa.data.schedule.OpenReferral
import dev.ohs.trustdemo.onesa.data.schedule.Schedule
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleService
import dev.ohs.trustdemo.onesa.data.schedule.VisitBooking
import dev.ohs.trustdemo.onesa.data.verification.DoseMatchService
import dev.ohs.trustdemo.onesa.data.verification.EpiDose
import dev.ohs.trustdemo.onesa.data.verification.ForeignDose
import dev.ohs.trustdemo.onesa.data.verification.ForeignDoses
import dev.ohs.trustdemo.onesa.data.verification.MatchOutcome
import dev.ohs.trustdemo.onesa.data.verification.Referral
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.data.verification.UnmatchedDose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The nurse's answer that a dose counts as no national schedule dose (DT.02 rule 4). */
val NotCounted = EpiDose("", "")

/**
 * S5's state: the referral, the schedule as evaluated and the outcome of booking the visit, with
 * the [child]'s household and audits and the coding of their foreign [doses]. A verified dose the
 * national map does not name waits in [unmatched] for the nurse's [choice] from [choices]; the
 * visit is booked only once none waits.
 */
data class ScheduleUiState(
  val referral: Referral? = null,
  val child: PatientContext? = null,
  val doses: List<ForeignDose> = emptyList(),
  val schedule: Schedule? = null,
  val busy: Boolean = true,
  val open: OpenReferral? = null,
  val booked: BookingOutcome.Booked? = null,
  val referralChanged: Boolean = false,
  val failed: Boolean = false,
  val syncFirst: Boolean = false,
  val unmatched: List<UnmatchedDose> = emptyList(),
  val choices: List<EpiDose> = emptyList(),
  val choice: EpiDose? = null,
  val matchChanged: Boolean = false,
  val couldNotMatch: Boolean = false,
  val listStale: Boolean = false,
) {
  val isOpen: Boolean
    get() = open?.status == "active"

  val canBook: Boolean
    get() =
      !busy &&
        booked == null &&
        unmatched.isEmpty() &&
        isOpen &&
        schedule?.complete == true &&
        schedule.hasDue
}

/**
 * Evaluates a referred child's national schedule, takes the nurse's equivalence for a dose the
 * national map does not name, and books the visit the schedule recommends.
 */
class ScheduleViewModel(
  private val referralId: String,
  private val inbox: ReferralInbox,
  private val service: ScheduleService,
  private val booking: VisitBooking,
  private val contexts: PatientContexts,
  private val foreign: ForeignDoses,
  private val matching: DoseMatchService,
) : ViewModel() {
  private val _uiState = MutableStateFlow(ScheduleUiState())
  val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

  private val leftUnmapped = mutableSetOf<String>()

  init {
    viewModelScope.launch { load() }
  }

  fun choose(choice: EpiDose) = _uiState.update { it.copy(choice = choice) }

  /**
   * The nurse confirms her choice for the first unmatched dose: DT.02 rule 3 writes the map, the
   * dose's second coding and her attestation; [NotCounted] (rule 4) writes nothing.
   */
  fun confirm() {
    val state = _uiState.value
    val referral = state.referral ?: return
    val dose = state.unmatched.firstOrNull() ?: return
    val choice = state.choice ?: return
    if (state.busy) return
    if (choice == NotCounted) {
      leftUnmapped += dose.id
      viewModelScope.launch { reload(referral) }
      return
    }
    _uiState.update { it.copy(busy = true, couldNotMatch = false, matchChanged = false) }
    viewModelScope.launch {
      val outcome = attempt { matching.confirm(dose, choice) }
      _uiState.update {
        it.copy(
          couldNotMatch = outcome == null,
          matchChanged = outcome is MatchOutcome.Changed,
          listStale = it.listStale || (outcome is MatchOutcome.Matched && outcome.listStale),
        )
      }
      reload(referral)
    }
  }

  /** The nurse books the visit: the recommendation, the reminder and the completed referral. */
  fun book() {
    val state = _uiState.value
    val referral = state.referral ?: return
    val open = state.open ?: return
    if (!state.canBook) return
    _uiState.update {
      it.copy(busy = true, failed = false, referralChanged = false, syncFirst = false)
    }
    viewModelScope.launch {
      when (val outcome = attempt { booking.book(open, referral.patientId) }) {
        is BookingOutcome.Booked -> {
          _uiState.update { it.copy(busy = false, booked = outcome, open = null) }
          reload(referral)
        }
        BookingOutcome.Changed,
        BookingOutcome.NoLongerOpen -> {
          reopen()
          reload(referral)
          _uiState.update { it.copy(referralChanged = true) }
        }
        else ->
          _uiState.update {
            it.copy(
              busy = false,
              failed = outcome == null,
              syncFirst = outcome == BookingOutcome.SyncFirst,
            )
          }
      }
    }
  }

  private suspend fun load() {
    val referral = inbox.get(referralId)
    _uiState.update { it.copy(referral = referral) }
    if (referral == null) _uiState.update { it.copy(busy = false, failed = true) }
    else {
      reopen()
      reload(referral)
    }
  }

  private suspend fun reopen() {
    val open = attempt { booking.open(referralId) }
    _uiState.update { it.copy(open = open, failed = open == null) }
  }

  private suspend fun reload(referral: Referral) {
    val child = attempt { contexts.of(referral.patientId) }
    val doses = attempt { foreign.of(referral.patientId) }.orEmpty()
    val unmatched =
      attempt { matching.unmatched(referral.patientId) }.orEmpty().filter { it.id !in leftUnmapped }
    val choices = if (unmatched.isEmpty()) emptyList() else attempt { matching.choices() }.orEmpty()
    _uiState.update {
      it.copy(
        child = child,
        doses = doses,
        unmatched = unmatched,
        choices = choices,
        choice = unmatched.firstOrNull()?.let { dose -> matching.proposal(dose, choices) },
      )
    }
    val schedule = attempt { service.evaluate(referral.patientId) }
    _uiState.update { it.copy(schedule = schedule, busy = false, failed = schedule == null) }
  }

  private suspend fun <T> attempt(block: suspend () -> T): T? =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
}
