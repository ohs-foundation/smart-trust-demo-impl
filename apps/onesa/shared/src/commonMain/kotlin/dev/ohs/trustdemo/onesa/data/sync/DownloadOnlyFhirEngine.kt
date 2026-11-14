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
import dev.ohs.fhir.engine.LocalChange
import dev.ohs.fhir.engine.db.LocalChangeResourceReference
import dev.ohs.fhir.engine.sync.upload.SyncUploadProgress
import dev.ohs.fhir.engine.sync.upload.UploadRequestResult
import dev.ohs.fhir.engine.sync.upload.UploadStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A [FhirEngine] whose sync never uploads, so a read-only user makes no write request at all. */
internal class DownloadOnlyFhirEngine(engine: FhirEngine) : FhirEngine by engine {
  @Deprecated("To be deprecated.")
  override suspend fun syncUpload(
    uploadStrategy: UploadStrategy,
    upload:
      suspend (List<LocalChange>, List<LocalChangeResourceReference>) -> Flow<UploadRequestResult>,
  ): Flow<SyncUploadProgress> = emptyFlow()
}
