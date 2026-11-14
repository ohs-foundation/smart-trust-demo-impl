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

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `$generate-vhl` input the Onesa VHL service parses: who, for what, until when, which code.
 */
object ShareRequestBody {
  const val PURPOSE = "TREAT"
  const val MIN_PASSCODE = 4
  const val MAX_PASSCODE = 12
  private const val MAX_DAYS = 365

  /** The last day a share starting on [start] may end: the service allows 365 days in all. */
  fun lastDay(start: LocalDate): LocalDate = start.plus(MAX_DAYS - 1, DateTimeUnit.DAY)

  /** The Parameters body; [passcode] goes out as the string it was typed. */
  fun of(people: List<String>, start: LocalDate, end: LocalDate, passcode: String): String =
    buildJsonObject {
        put("resourceType", "Parameters")
        putJsonArray("parameter") {
          people.forEach { id ->
            add(
              buildJsonObject {
                put("name", "person")
                putJsonObject("valueReference") { put("reference", "Patient/$id") }
              }
            )
          }
          add(
            buildJsonObject {
              put("name", "purpose")
              put("valueCode", PURPOSE)
            }
          )
          add(
            buildJsonObject {
              put("name", "period")
              putJsonObject("valuePeriod") {
                put("start", start.toString())
                put("end", end.toString())
              }
            }
          )
          add(
            buildJsonObject {
              put("name", "passcode")
              put("valueString", passcode)
            }
          )
        }
      }
      .toString()
}
