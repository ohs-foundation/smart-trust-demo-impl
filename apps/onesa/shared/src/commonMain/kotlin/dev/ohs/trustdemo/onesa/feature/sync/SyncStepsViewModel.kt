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
package dev.ohs.trustdemo.onesa.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.trustdemo.onesa.auth.UserContext
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.sync.DownloadProgress
import dev.ohs.trustdemo.onesa.data.sync.loadSyncConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The rows of the first-sync screen, and the [place] being made ready once its record arrives. */
data class SyncStepsState(val steps: List<SyncStep> = emptyList(), val place: String? = null)

/** Refreshes [SyncStepsState] as the download moves from type to type. */
class SyncStepsViewModel(
  private val repository: FhirRepository,
  private val context: () -> UserContext?,
) : ViewModel() {
  private val _state = MutableStateFlow(SyncStepsState())
  val state: StateFlow<SyncStepsState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      val user = context() ?: return@launch
      val order = loadSyncConfig(user.role).resources.map { it.type }
      DownloadProgress.status.collect { status ->
        val steps =
          syncSteps(order, status).map { (part, state) ->
            if (state == StepState.Waiting) return@map SyncStep(part, state)
            val found = part.types.filter { it in order }.flatMap { repository.all(it) }
            SyncStep(part, state, found.size, stepNames(part, found, user.practitionerId))
          }
        _state.value = SyncStepsState(steps, place(user))
      }
    }
  }

  private suspend fun place(user: UserContext): String? =
    user.locationId?.let { repository.get("Location", it) }?.let(::syncedName)
      ?: user.organizationId?.let { repository.get("Organization", it) }?.let(::syncedName)
}
