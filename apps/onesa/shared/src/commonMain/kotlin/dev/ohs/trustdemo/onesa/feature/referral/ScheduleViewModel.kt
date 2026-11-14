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
import dev.ohs.trustdemo.onesa.data.schedule.BookingOutcome
import dev.ohs.trustdemo.onesa.data.schedule.OpenReferral
import dev.ohs.trustdemo.onesa.data.schedule.Schedule
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleService
import dev.ohs.trustdemo.onesa.data.schedule.VisitBooking
import dev.ohs.trustdemo.onesa.data.verification.Referral
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** S5's state: the referral, the schedule as evaluated and the outcome of booking the visit. */
data class ScheduleUiState(
  val referral: Referral? = null,
  val schedule: Schedule? = null,
  val busy: Boolean = true,
  val open: OpenReferral? = null,
  val booked: BookingOutcome.Booked? = null,
  val referralChanged: Boolean = false,
  val failed: Boolean = false,
  val syncFirst: Boolean = false,
) {
  val isOpen: Boolean
    get() = open?.status == "active"

  val canBook: Boolean
    get() = !busy && booked == null && isOpen && schedule?.complete == true && schedule.hasDue
}

/** Evaluates a referred child's national schedule and books the visit it recommends. */
class ScheduleViewModel(
  private val referralId: String,
  private val inbox: ReferralInbox,
  private val service: ScheduleService,
  private val booking: VisitBooking,
) : ViewModel() {
  private val _uiState = MutableStateFlow(ScheduleUiState())
  val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch { load() }
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
    val schedule = attempt { service.evaluate(referral.patientId) }
    _uiState.update { it.copy(schedule = schedule, busy = false, failed = schedule == null) }
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
