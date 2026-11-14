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
package dev.ohs.trustdemo.onesa.util

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The moment as local `date hh:mm`, for the screens that say when something was fetched. */
fun displayTime(epochSeconds: Long): String {
  val local =
    Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
  return "${local.date} ${local.hour.toString().padStart(2, '0')}:" +
    local.minute.toString().padStart(2, '0')
}

/** A day as the clinic screens write it: `25 Nov 2025`. */
fun displayDate(date: LocalDate): String = "${dayMonth(date)} ${date.year}"

/** A day without its year: `9 Dec`. */
fun dayMonth(date: LocalDate): String =
  "${date.day} ${date.month.name.take(3).lowercase().replaceFirstChar(Char::uppercaseChar)}"

/** A window of days: `9–16 Dec 2025`, or `28 Nov – 5 Dec 2025` across months. */
fun displayRange(from: LocalDate, to: LocalDate): String =
  when {
    from.year != to.year -> "${displayDate(from)} – ${displayDate(to)}"
    from.month != to.month -> "${dayMonth(from)} – ${displayDate(to)}"
    else -> "${from.day}–${displayDate(to)}"
  }

/** How long before [nowEpochSeconds] the moment was: `just now`, `5m ago`, `2h ago`, `3d ago`. */
fun timeAgo(epochSeconds: Long, nowEpochSeconds: Long): String {
  val minutes = (nowEpochSeconds - epochSeconds).coerceAtLeast(0) / 60
  return when {
    minutes < 1 -> "just now"
    minutes < 60 -> "${minutes}m ago"
    minutes < 24 * 60 -> "${minutes / 60}h ago"
    else -> "${minutes / (24 * 60)}d ago"
  }
}
