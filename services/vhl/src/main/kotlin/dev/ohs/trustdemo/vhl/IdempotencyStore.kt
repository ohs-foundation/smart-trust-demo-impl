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

import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The same key was used with a different request body. */
class KeyReused(message: String) : Exception(message)

private val KEEP = Duration.ofMinutes(10)
private const val MAX_ENTRIES = 1_000

/**
 * Remembers, in memory, what a caller's `Idempotency-Key` produced for 10 minutes, so a retried
 * request gets the same reply instead of a second set of records. The stored reply is never logged.
 */
class IdempotencyStore(private val clock: Clock) {
  private class Entry(val digest: String, val reply: String, val at: Instant)

  private val lock = Mutex()
  private val entries = LinkedHashMap<Pair<String, String>, Entry>()

  /** The reply for ([caller], [key]): stored when [body] matches, else from [produce]. */
  suspend fun once(
    caller: String,
    key: String,
    body: String,
    produce: suspend () -> String,
  ): String =
    lock.withLock {
      val now = clock.instant()
      entries.entries.removeAll { Duration.between(it.value.at, now) > KEEP }
      val digest = sha256(body)
      val kept = entries[caller to key]
      if (kept != null) {
        if (kept.digest != digest) throw KeyReused("the Idempotency-Key was used with another body")
        return kept.reply
      }
      val reply = produce()
      while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
      entries[caller to key] = Entry(digest, reply, now)
      reply
    }

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
      "%02x".format(it)
    }
}
