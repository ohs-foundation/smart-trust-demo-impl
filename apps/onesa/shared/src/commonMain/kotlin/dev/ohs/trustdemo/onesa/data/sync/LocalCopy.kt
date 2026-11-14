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
package dev.ohs.trustdemo.onesa.data.sync

import dev.ohs.fhir.engine.sync.SyncJobStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Runs a post-write [refresh] and says whether it succeeded. A failure is reported, never thrown,
 * so the write that came before it still counts as done.
 */
internal suspend fun succeeded(refresh: suspend () -> Boolean): Boolean =
  try {
    refresh()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    false
  }

/**
 * A [SyncManager] that knows whether this phone's records have caught up with the last write. A
 * write goes straight to the gateway, so the local copy lags until a sync downloads it; a failed
 * sync leaves the copy behind until the next successful one, from any screen.
 */
class LocalCopy(private val delegate: SyncManager) : SyncManager by delegate {
  private val behind = MutableStateFlow(false)

  /** False between a write and the sync that brings it down. */
  fun caughtUp(): Boolean = !behind.value

  override suspend fun syncNow(): SyncJobStatus =
    delegate.syncNow().also { if (it is SyncJobStatus.Succeeded) behind.value = false }

  /** Syncs after a write; true when the local copy holds it. */
  suspend fun refreshAfterWrite(): Boolean {
    behind.value = true
    return succeeded { syncNow() is SyncJobStatus.Succeeded }
  }
}
