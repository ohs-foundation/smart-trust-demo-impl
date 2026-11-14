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
package dev.ohs.trustdemo.vhl

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A request the caller got wrong; [message] says how. */
class BadRequest(message: String) : Exception(message)

/** The caller is not allowed to share what they asked to share. */
class NotYourHousehold(message: String) : Exception(message)

class NotYourConsent(message: String) : Exception(message)

class NoSuchConsent(message: String) : Exception(message)

class WithdrawConflict(message: String) : Exception(message)

private const val MIN_PASSCODE = 4
private const val MAX_PASSCODE = 12
private const val PURPOSE = "TREAT"
private val MAX_PERIOD = Duration.ofDays(365)
private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** The `$generate-vhl` input: who to share, for what, until when, behind which passcode. */
class ShareRequest(
  val people: List<String>,
  val periodStart: String,
  val periodEnd: String,
  val passcode: String,
) {
  /** The instant the consent starts: the start of the day when the period starts on a date. */
  val starts: Instant = instantOf(periodStart, endOfDay = false)

  /** The instant the consent ends: the end of the day when the period ends on a date. */
  val expires: Instant = instantOf(periodEnd, endOfDay = true)

  companion object {
    fun parse(text: String): ShareRequest {
      val parts =
        try {
          Json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
          throw BadRequest("the body is not a FHIR Parameters resource")
        } catch (e: IllegalArgumentException) {
          throw BadRequest("the body is not a FHIR Parameters resource")
        }
      if (parts["resourceType"]?.jsonPrimitive?.content != "Parameters") {
        throw BadRequest("the body is not a FHIR Parameters resource")
      }
      val named = parts["parameter"]?.jsonArray?.map { it.jsonObject }.orEmpty()

      fun all(name: String) = named.filter { it.string("name") == name }

      fun one(name: String) =
        all(name).singleOrNull() ?: throw BadRequest("exactly one $name is needed")

      val people =
        all("person").map {
          val reference = it["valueReference"]?.jsonObject?.string("reference")
          reference?.takeIf { ref -> ref.startsWith("Patient/") }?.removePrefix("Patient/")
            ?: throw BadRequest("each person is a Patient reference")
        }
      if (people.isEmpty()) throw BadRequest("at least one person is needed")
      if (one("purpose").string("valueCode") != PURPOSE) {
        throw BadRequest("the only purpose is $PURPOSE")
      }
      val period =
        one("period")["valuePeriod"]?.jsonObject ?: throw BadRequest("period is a Period")
      val passcode =
        one("passcode").string("valueString") ?: throw BadRequest("passcode is a string")
      if (passcode.length !in MIN_PASSCODE..MAX_PASSCODE) {
        throw BadRequest("the passcode is $MIN_PASSCODE to $MAX_PASSCODE characters")
      }
      val request =
        try {
          ShareRequest(
            people = people.distinct(),
            periodStart = period.string("start") ?: throw BadRequest("the period needs a start"),
            periodEnd = period.string("end") ?: throw BadRequest("the period needs an end"),
            passcode = passcode,
          )
        } catch (e: DateTimeParseException) {
          throw BadRequest("the period start and end are dates or dateTimes")
        }
      if (request.starts.isAfter(request.expires))
        throw BadRequest("the period ends before it starts")
      if (Duration.between(request.starts, request.expires) > MAX_PERIOD) {
        throw BadRequest("the period is at most ${MAX_PERIOD.toDays()} days")
      }
      return request
    }

    private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.content

    private fun instantOf(text: String, endOfDay: Boolean): Instant =
      if (DATE.matches(text)) {
        LocalDate.parse(text)
          .plusDays(if (endOfDay) 1 else 0)
          .atStartOfDay()
          .toInstant(ZoneOffset.UTC)
      } else {
        OffsetDateTime.parse(text).toInstant()
      }
  }
}

private val CONSENT_REFERENCE = Regex("""Consent/([A-Za-z0-9\-.]{1,64})""")

/** The id of the Consent a withdraw Parameters names. */
fun consentOf(text: String): String {
  val reference =
    try {
      Json.parseToJsonElement(text)
        .jsonObject["parameter"]
        ?.jsonArray
        ?.map { it.jsonObject }
        ?.singleOrNull { it["name"]?.jsonPrimitive?.content == "consent" }
        ?.get("valueReference")
        ?.jsonObject
        ?.get("reference")
        ?.jsonPrimitive
        ?.content
    } catch (e: SerializationException) {
      null
    } catch (e: IllegalArgumentException) {
      null
    }
  return reference?.let { CONSENT_REFERENCE.matchEntire(it)?.groupValues?.get(1) }
    ?: throw BadRequest("exactly one consent reference is needed")
}
