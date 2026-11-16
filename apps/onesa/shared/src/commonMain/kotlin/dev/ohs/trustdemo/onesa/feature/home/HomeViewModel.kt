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
package dev.ohs.trustdemo.onesa.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.fhir.engine.sync.FhirDataStore
import dev.ohs.fhir.engine.sync.SyncJobStatus
import dev.ohs.trustdemo.onesa.data.sync.SyncManager
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the last sync ended without success — the screen maps each to a localized message. */
enum class SyncError {
  Failed,
  Cancelled,
}

/** The least time between two syncs that reaching Home starts. */
private val ARRIVAL_SYNC_INTERVAL = 30.seconds

data class HomeUiState(
  val isSyncing: Boolean = false,
  val lastSyncedAt: Instant? = null,
  val syncError: SyncError? = null,
)

class HomeViewModel(
  private val syncManager: SyncManager,
  private val fhirDataStore: FhirDataStore,
  private val clock: Clock = Clock.System,
) : ViewModel() {
  private val _uiState = MutableStateFlow(HomeUiState())
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  private var cancelRequested = false
  private var lastArrivalSync: Instant? = null

  init {
    viewModelScope.launch {
      val lastSyncedAt = fhirDataStore.readLastSyncTimestamp()
      _uiState.update { it.copy(lastSyncedAt = lastSyncedAt) }
    }
  }

  /**
   * Triggers a one-time sync. Returns `null` without starting a new sync if one is already in
   * progress; otherwise returns the launched [Job] (primarily so tests can `join()` it).
   */
  fun syncNow(): Job? = sync(quiet = false)

  /**
   * Syncs because Home was reached: at most once per [ARRIVAL_SYNC_INTERVAL], never while a sync
   * runs, and quietly, as the header's sync chip already says when the phone is behind.
   */
  fun syncOnArrival(): Job? {
    val now = clock.now()
    if (lastArrivalSync?.let { now - it < ARRIVAL_SYNC_INTERVAL } == true) return null
    return sync(quiet = true)?.also { lastArrivalSync = now }
  }

  private fun sync(quiet: Boolean): Job? {
    if (_uiState.value.isSyncing) return null
    cancelRequested = false
    _uiState.update { it.copy(isSyncing = true, syncError = null) }
    return viewModelScope.launch {
      val result =
        try {
          syncManager.syncNow()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          SyncJobStatus.Failed()
        }
      when (result) {
        is SyncJobStatus.Succeeded -> {
          val lastSyncedAt = fhirDataStore.readLastSyncTimestamp()
          _uiState.update { it.copy(isSyncing = false, lastSyncedAt = lastSyncedAt) }
        }
        else -> {
          val error =
            when {
              cancelRequested -> SyncError.Cancelled
              quiet -> null
              else -> SyncError.Failed
            }
          _uiState.update { it.copy(isSyncing = false, syncError = error) }
        }
      }
    }
  }

  /** Cancels an in-flight [syncNow]. No-op if no sync is currently running. */
  fun cancelSync() {
    if (!_uiState.value.isSyncing) return
    cancelRequested = true
    viewModelScope.launch { syncManager.cancelSyncNow() }
  }

  fun clearSyncError() {
    _uiState.update { it.copy(syncError = null) }
  }
}
