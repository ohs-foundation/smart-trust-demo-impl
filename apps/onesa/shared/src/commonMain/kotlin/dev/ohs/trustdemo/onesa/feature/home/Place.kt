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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.auth.SessionRepository
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.feature.sync.syncedName
import org.koin.compose.koinInject

/** Where the signed-in user works: the CHW's catchment, or the clinician's facility. */
@Composable
fun rememberPlace(): String? {
  val repository: FhirRepository = koinInject()
  val session by SessionRepository.session.collectAsStateWithLifecycle()
  val user = session?.context
  val place by
    produceState<String?>(null, user) {
      value =
        user?.locationId?.let { repository.get("Location", it) }?.let(::syncedName)
          ?: user?.organizationId?.let { repository.get("Organization", it) }?.let(::syncedName)
    }
  return place
}
