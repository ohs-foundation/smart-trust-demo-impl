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
package dev.ohs.trustdemo.onesa.feature.patient.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.trustdemo.onesa.data.repository.PatientRepository
import dev.ohs.trustdemo.onesa.data.schedule.DoseEntry
import dev.ohs.trustdemo.onesa.data.schedule.DoseProblem
import dev.ohs.trustdemo.onesa.data.schedule.DoseRecording
import dev.ohs.trustdemo.onesa.data.schedule.RecordOutcome
import dev.ohs.trustdemo.onesa.data.verification.EpiDose
import dev.ohs.trustdemo.onesa.generateId
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** The "Record a dose" form: what the nurse has picked and typed, and what the last try came to. */
data class RecordDoseForm(
  val doses: List<EpiDose> = emptyList(),
  val dose: EpiDose? = null,
  val products: List<EpiDose> = emptyList(),
  val product: EpiDose? = null,
  val lotNumber: String = "",
  val date: String = "",
  val problem: DoseProblem? = null,
  val dateUnreadable: Boolean = false,
  val alreadyRecorded: LocalDate? = null,
  val busy: Boolean = false,
  val failed: Boolean = false,
  val syncFirst: Boolean = false,
  val recordingId: String = generateId(),
)

class PatientProfileViewModel(
  private val patientId: String,
  patientRepository: PatientRepository,
  private val recording: DoseRecording,
) : ViewModel() {
  private val _uiState = MutableStateFlow<ProfileUiState?>(null)
  val uiState: StateFlow<ProfileUiState?> = _uiState.asStateFlow()
  private val _form = MutableStateFlow<RecordDoseForm?>(null)
  val form: StateFlow<RecordDoseForm?> = _form.asStateFlow()
  private val _recordedStale = MutableStateFlow(false)

  /** True when the last dose is recorded but the local copy could not be refreshed. */
  val recordedStale: StateFlow<Boolean> = _recordedStale.asStateFlow()

  init {
    viewModelScope.launch {
      patientRepository.observePatientProfile(patientId).collect { _uiState.value = it }
    }
  }

  fun openForm() {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
    _recordedStale.value = false
    viewModelScope.launch {
      val doses = attempt { recording.doses() }.orEmpty()
      _form.value = RecordDoseForm(doses = doses, date = today, failed = doses.isEmpty())
    }
  }

  fun closeForm() {
    _form.value = null
  }

  fun pickDose(dose: EpiDose) {
    viewModelScope.launch {
      val products = attempt { recording.products(dose) }.orEmpty()
      _form.update {
        it?.copy(
          dose = dose,
          products = products,
          product = products.singleOrNull(),
          alreadyRecorded = null,
        )
      }
    }
  }

  fun pickProduct(product: EpiDose) = edit { it.copy(product = product) }

  fun typeLot(lotNumber: String) = edit { it.copy(lotNumber = lotNumber) }

  fun typeDate(date: String) = edit { it.copy(date = date) }

  /** Records the dose; with [confirmed] a dose that already counts is recorded again. */
  fun record(confirmed: Boolean = false) {
    val form = _form.value ?: return
    val dose = form.dose ?: return
    val date = runCatching { LocalDate.parse(form.date) }.getOrNull()
    if (date == null) {
      _form.update { it?.copy(dateUnreadable = true) }
      return
    }
    _form.update {
      it?.copy(
        busy = true,
        failed = false,
        syncFirst = false,
        problem = null,
        dateUnreadable = false,
      )
    }
    viewModelScope.launch {
      val entry =
        DoseEntry(patientId, dose, form.product?.code, form.lotNumber, date, id = form.recordingId)
      when (val outcome = attempt { recording.record(entry, confirmed) }) {
        is RecordOutcome.Recorded -> {
          _recordedStale.value = outcome.listStale
          _form.value = null
        }
        RecordOutcome.SyncFirst -> _form.update { it?.copy(busy = false, syncFirst = true) }
        is RecordOutcome.Refused ->
          _form.update { it?.copy(busy = false, problem = outcome.problem) }
        is RecordOutcome.AlreadyRecorded ->
          _form.update { it?.copy(busy = false, alreadyRecorded = outcome.date) }
        null -> _form.update { it?.copy(busy = false, failed = true) }
      }
    }
  }

  private fun edit(change: (RecordDoseForm) -> RecordDoseForm) =
    _form.update { it?.let(change)?.copy(problem = null, alreadyRecorded = null) }

  private suspend fun <T : Any> attempt(block: suspend () -> T): T? =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
}
