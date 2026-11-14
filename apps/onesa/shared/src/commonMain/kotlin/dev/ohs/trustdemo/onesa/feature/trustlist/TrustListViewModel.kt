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
package dev.ohs.trustdemo.onesa.feature.trustlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.trustdemo.onesa.data.hcert.HcertService
import dev.ohs.trustdemo.onesa.data.hcert.TrustListStatus
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class TrustListViewModel(private val hcert: HcertService) : ViewModel() {
  private val _lists = MutableStateFlow<List<TrustListState>?>(null)
  val lists: StateFlow<List<TrustListState>?> = _lists.asStateFlow()

  init {
    load()
  }

  fun load() {
    viewModelScope.launch { _lists.value = hcert.statuses().map { it.toState() } }
  }
}

private fun TrustListStatus.toState() =
  TrustListState(
    name = name,
    source = source.ifBlank { null },
    signer = signer.ifBlank { null },
    fetchedAt = fetchedAtEpochSeconds?.let { Instant.fromEpochSeconds(it).toDisplayString() },
    entryCount = entryCount.toString(),
    domains = domains.joinToString().ifBlank { null },
  )

private fun Instant.toDisplayString(): String {
  val local = toLocalDateTime(TimeZone.currentSystemDefault())
  return "${local.date} ${local.hour.toString().padStart(2, '0')}:" +
    local.minute.toString().padStart(2, '0')
}
