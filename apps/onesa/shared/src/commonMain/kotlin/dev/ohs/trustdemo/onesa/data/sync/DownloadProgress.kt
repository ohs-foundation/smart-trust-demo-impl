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

import dev.ohs.fhir.engine.sync.download.DownloadRequest
import dev.ohs.fhir.engine.sync.download.UrlDownloadRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How far a download has got: the resource [type] it is on, or [finished]. */
data class DownloadStatus(val type: String? = null, val finished: Boolean = false)

/**
 * The running download's progress, for the first-sync screen. A plain `object`, like the data
 * change signal, because the sync runs in a worker the screen cannot reach.
 */
object DownloadProgress {
  private val _status = MutableStateFlow(DownloadStatus())
  val status: StateFlow<DownloadStatus> = _status.asStateFlow()

  internal fun started() {
    _status.value = DownloadStatus()
  }

  /** A first page names its type (`Group?_count=100`); later pages are absolute links. */
  internal fun requested(request: DownloadRequest?) {
    when (request) {
      null -> _status.value = DownloadStatus(finished = true)
      is UrlDownloadRequest ->
        request.url
          .substringBefore('?')
          .takeIf { it.all(Char::isLetter) }
          ?.let { _status.value = DownloadStatus(type = it) }
      else -> Unit
    }
  }
}
