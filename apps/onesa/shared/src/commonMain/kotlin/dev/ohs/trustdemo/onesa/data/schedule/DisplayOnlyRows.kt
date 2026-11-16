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
package dev.ohs.trustdemo.onesa.data.schedule

import dev.ohs.trustdemo.onesa.generated.resources.Res
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Doses the IG's S5 screen shows at an age ([label], [value] [unit] in UCUM) that the IG's
 * schedule, an illustrative excerpt, does not hold. They show as upcoming, with no schedule code,
 * and are never evaluated or booked.
 */
@Serializable
data class DisplayOnlyRow(
  val label: String,
  val value: Int,
  val unit: String,
  val doses: List<String>,
) {
  fun due(birthDate: LocalDate): LocalDate =
    birthDate.plus(value, if (unit == "mo") DateTimeUnit.MONTH else DateTimeUnit.WEEK)
}

/** The rows bundled with the app, read for S5 and the patient profile's Schedule tab. */
object DisplayOnlyRows {
  @Serializable private class Rows(val rows: List<DisplayOnlyRow>)

  private val json = Json { ignoreUnknownKeys = true }

  fun parse(text: String): List<DisplayOnlyRow> = json.decodeFromString<Rows>(text).rows

  @OptIn(ExperimentalResourceApi::class)
  suspend fun load(): List<DisplayOnlyRow> =
    parse(Res.readBytes("files/schedule/s5-display-only-rows.json").decodeToString())
}
