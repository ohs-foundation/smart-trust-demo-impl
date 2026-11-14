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
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

enum class PasscodeCheck {
  MATCH,
  WRONG,
  UNKNOWN,
}

/** The passcode of each share, keyed by the share's Consent id. Passcodes are strings. */
interface PasscodeStore {
  suspend fun keep(consentId: String, passcode: String)

  /** Compares in constant time; a [PasscodeCheck.WRONG] answer counts as a wrong attempt. */
  suspend fun check(consentId: String, passcode: String): PasscodeCheck

  suspend fun wrongAttempts(consentId: String): Int

  suspend fun remove(consentId: String)
}

private const val MEMORY_KIB = 19_456
private const val ITERATIONS = 2
private const val PARALLELISM = 1
private const val HASH_BYTES = 32
private const val SALT_BYTES = 16

/** Argon2id hashes (OWASP parameters, kept with each hash) in an embedded H2 database. */
class H2PasscodeStore(private val jdbcUrl: String) : PasscodeStore {
  private val random = SecureRandom()

  init {
    connect().use {
      it.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE IF NOT EXISTS passcode (
            consent_id VARCHAR(64) PRIMARY KEY,
            hash VARBINARY(32) NOT NULL,
            salt VARBINARY(16) NOT NULL,
            memory_kib INT NOT NULL,
            iterations INT NOT NULL,
            parallelism INT NOT NULL,
            wrong_attempts INT DEFAULT 0 NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL
          )
          """
            .trimIndent()
        )
      }
    }
  }

  override suspend fun keep(consentId: String, passcode: String) {
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val hash = hash(passcode, salt, MEMORY_KIB, ITERATIONS, PARALLELISM)
    sql { connection ->
      connection
        .prepareStatement(
          "INSERT INTO passcode (consent_id, hash, salt, memory_kib, iterations, parallelism, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        .use {
          it.setString(1, consentId)
          it.setBytes(2, hash)
          it.setBytes(3, salt)
          it.setInt(4, MEMORY_KIB)
          it.setInt(5, ITERATIONS)
          it.setInt(6, PARALLELISM)
          it.setObject(7, Timestamp.from(Instant.now()))
          it.executeUpdate()
        }
    }
  }

  override suspend fun check(consentId: String, passcode: String): PasscodeCheck {
    val stored =
      sql { connection ->
        connection
          .prepareStatement(
            "SELECT hash, salt, memory_kib, iterations, parallelism FROM passcode WHERE consent_id = ?"
          )
          .use {
            it.setString(1, consentId)
            it.executeQuery().use { row ->
              if (row.next())
                Stored(
                  row.getBytes(1),
                  row.getBytes(2),
                  row.getInt(3),
                  row.getInt(4),
                  row.getInt(5),
                )
              else null
            }
          }
      } ?: return PasscodeCheck.UNKNOWN
    val candidate =
      hash(passcode, stored.salt, stored.memoryKib, stored.iterations, stored.parallelism)
    if (MessageDigest.isEqual(candidate, stored.hash)) return PasscodeCheck.MATCH
    sql { connection ->
      connection
        .prepareStatement(
          "UPDATE passcode SET wrong_attempts = wrong_attempts + 1 WHERE consent_id = ?"
        )
        .use {
          it.setString(1, consentId)
          it.executeUpdate()
        }
    }
    return PasscodeCheck.WRONG
  }

  override suspend fun wrongAttempts(consentId: String): Int = sql { connection ->
    connection.prepareStatement("SELECT wrong_attempts FROM passcode WHERE consent_id = ?").use {
      it.setString(1, consentId)
      it.executeQuery().use { row -> if (row.next()) row.getInt(1) else 0 }
    }
  }

  override suspend fun remove(consentId: String) {
    sql { connection ->
      connection.prepareStatement("DELETE FROM passcode WHERE consent_id = ?").use {
        it.setString(1, consentId)
        it.executeUpdate()
      }
    }
  }

  private class Stored(
    val hash: ByteArray,
    val salt: ByteArray,
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
  )

  private fun connect(): Connection = DriverManager.getConnection(jdbcUrl)

  private suspend fun <T> sql(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) { connect().use(block) }

  private fun hash(
    passcode: String,
    salt: ByteArray,
    memoryKib: Int,
    iterations: Int,
    parallelism: Int,
  ) =
    ByteArray(HASH_BYTES).also {
      Argon2BytesGenerator()
        .apply {
          init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
              .withSalt(salt)
              .withMemoryAsKB(memoryKib)
              .withIterations(iterations)
              .withParallelism(parallelism)
              .build()
          )
        }
        .generateBytes(passcode.toByteArray(Charsets.UTF_8), it)
    }
}
