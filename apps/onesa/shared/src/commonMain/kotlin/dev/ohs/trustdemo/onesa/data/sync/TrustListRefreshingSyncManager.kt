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
import dev.ohs.trustdemo.onesa.data.hcert.HcertService

/** A [SyncManager] that refreshes the local trust list after each one-time sync. */
class TrustListRefreshingSyncManager(
  private val delegate: SyncManager,
  private val hcert: HcertService,
) : SyncManager by delegate {
  override suspend fun syncNow(): SyncJobStatus =
    delegate.syncNow().also { hcert.refreshLocalListQuietly() }
}
