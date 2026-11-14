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
package dev.ohs.trustdemo.onesa.data.sharing

import kotlinx.datetime.LocalDate

/** Why a share cannot be requested yet. */
enum class ShareProblem {
  NoOne,
  EndOutOfRange,
  PasscodeLength,
  PasscodesDiffer,
}

/** What the holder entered: who, the last day, and the passcode typed twice, as strings. */
data class ShareEntry(
  val people: List<String>,
  val end: LocalDate,
  val passcode: String,
  val again: String,
) {
  fun problem(today: LocalDate): ShareProblem? =
    when {
      people.isEmpty() -> ShareProblem.NoOne
      end < today || end > ShareRequestBody.lastDay(today) -> ShareProblem.EndOutOfRange
      passcode.length !in ShareRequestBody.MIN_PASSCODE..ShareRequestBody.MAX_PASSCODE ->
        ShareProblem.PasscodeLength
      passcode != again -> ShareProblem.PasscodesDiffer
      else -> null
    }
}
