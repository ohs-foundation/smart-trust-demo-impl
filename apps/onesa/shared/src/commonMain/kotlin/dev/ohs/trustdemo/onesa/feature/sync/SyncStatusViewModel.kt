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
import dev.ohs.trustdemo.onesa.data.sync.loadSyncConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A record waiting on this phone: what it is ([label]) and its [reference]. */
data class PendingUpload(val label: String, val reference: String)

/** Reads what waits to upload, for the role's record types. */
class SyncStatusViewModel(
  private val repository: FhirRepository,
  private val context: () -> UserContext?,
) : ViewModel() {
  private val _pending = MutableStateFlow<List<PendingUpload>>(emptyList())
  val pending: StateFlow<List<PendingUpload>> = _pending.asStateFlow()

  fun refresh(): Job =
    viewModelScope.launch {
      val types = context()?.let { loadSyncConfig(it.role).resources.map { r -> r.type } }.orEmpty()
      val pending =
        types.flatMap { type ->
          repository.unsynced(type).map { resource ->
            PendingUpload(syncedName(resource) ?: type, "$type/${resource.id}")
          }
        }
      _pending.value = pending
    }
}
