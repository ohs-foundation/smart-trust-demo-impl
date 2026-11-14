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

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.sync.AcceptLocalConflictResolver
import dev.ohs.fhir.engine.sync.ConflictResolver
import dev.ohs.fhir.engine.sync.DownloadWorkManager
import dev.ohs.fhir.engine.sync.FhirSyncTask
import dev.ohs.fhir.engine.sync.upload.HttpCreateMethod
import dev.ohs.fhir.engine.sync.upload.HttpUpdateMethod
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.SessionRepository

const val SYNC_TIMEOUT_DURATION = 120L

/**
 * This app's [FhirSyncTask]: downloads what the signed-in user's role names in `files/sync`,
 * resolves conflicts in favor of the local change, and uploads pending local changes as a single
 * bundle request. A family user's sync is download-only, because the gateway refuses their writes.
 */
class AppFhirSyncTask(private val fhirEngine: FhirEngine) : FhirSyncTask {
  private val timestampContext = DataStoreTimestampContext(createSyncTimestampDataStore())

  override fun getFhirEngine(): FhirEngine =
    if (SessionRepository.session.value?.context?.role == AppRole.FAMILY) {
      DownloadOnlyFhirEngine(fhirEngine)
    } else {
      fhirEngine
    }

  override fun getDownloadWorkManager(): DownloadWorkManager =
    RoleDownloadWorkManager(SessionRepository.session.value?.context, timestampContext)

  override fun getConflictResolver(): ConflictResolver = AcceptLocalConflictResolver

  override fun getUploadStrategy(): UploadStrategy =
    UploadStrategy.forBundleRequest(
      methodForCreate = HttpCreateMethod.PUT,
      methodForUpdate = HttpUpdateMethod.PATCH,
      squash = true,
      bundleSize = 500,
    )
}
