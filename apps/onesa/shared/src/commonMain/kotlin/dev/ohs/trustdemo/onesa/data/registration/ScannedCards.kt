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
package dev.ohs.trustdemo.onesa.data.registration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The cards read during one registration visit. They live in memory only: nothing is stored until
 * the registration is posted, and the list is cleared when it is.
 */
class ScannedCards {
  private val _cards = MutableStateFlow<List<ScannedCard>>(emptyList())
  val cards: StateFlow<List<ScannedCard>> = _cards.asStateFlow()

  /** Adds [card] unless the same HC1 text was already read; returns whether it was added. */
  fun add(card: ScannedCard): Boolean {
    var added = false
    _cards.update { current ->
      if (current.any { it.hc1.trim() == card.hc1.trim() }) current
      else current.also { added = true } + card
    }
    return added
  }

  fun clear() {
    _cards.value = emptyList()
  }
}
