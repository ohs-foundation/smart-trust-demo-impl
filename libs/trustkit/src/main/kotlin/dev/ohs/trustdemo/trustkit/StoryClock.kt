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
package dev.ohs.trustdemo.trustkit

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Maps the IG's story dates onto the calendar, so the J3 anchor day falls on a real day. */
class StoryClock(private val anchorIg: LocalDate, private val anchorReal: LocalDate) {
  fun real(ig: LocalDate): LocalDate = ig.plusDays(shiftDays())

  fun shiftDays(): Long = ChronoUnit.DAYS.between(anchorIg, anchorReal)

  companion object {
    /** Records already on the real calendar. */
    val NONE = StoryClock(LocalDate.EPOCH, LocalDate.EPOCH)
  }
}
