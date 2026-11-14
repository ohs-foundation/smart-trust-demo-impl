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

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A manifest released to the clinic with key [kid]; its document may now be fetched. */
class Release(
  val listId: String,
  val binaryId: String,
  val kid: String,
  val patientId: String,
  val consentId: String,
  val bundleId: String,
  val at: Instant,
)

interface ReleaseStore {
  suspend fun record(release: Release)

  /** The latest release of [binaryId], to the clinic with [kid] or, when null, to any clinic. */
  suspend fun latest(binaryId: String, kid: String?): Release?
}

/** Releases in the service's embedded H2 database. */
class H2ReleaseStore(private val jdbcUrl: String) : ReleaseStore {
  init {
    connect().use {
      it.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE IF NOT EXISTS vhl_release (
            list_id VARCHAR(64) NOT NULL,
            binary_id VARCHAR(64) NOT NULL,
            kid VARCHAR(32) NOT NULL,
            patient_id VARCHAR(64) NOT NULL,
            consent_id VARCHAR(64) NOT NULL,
            bundle_id VARCHAR(64) NOT NULL,
            released_at TIMESTAMP WITH TIME ZONE NOT NULL
          )
          """
            .trimIndent()
        )
      }
    }
  }

  override suspend fun record(release: Release) {
    sql { connection ->
      connection
        .prepareStatement(
          "INSERT INTO vhl_release (list_id, binary_id, kid, patient_id, consent_id, bundle_id, released_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        .use {
          it.setString(1, release.listId)
          it.setString(2, release.binaryId)
          it.setString(3, release.kid)
          it.setString(4, release.patientId)
          it.setString(5, release.consentId)
          it.setString(6, release.bundleId)
          it.setObject(7, Timestamp.from(release.at))
          it.executeUpdate()
        }
    }
  }

  override suspend fun latest(binaryId: String, kid: String?): Release? = sql { connection ->
    connection
      .prepareStatement(
        "SELECT list_id, binary_id, kid, patient_id, consent_id, bundle_id, released_at FROM vhl_release " +
          "WHERE binary_id = ? AND (? IS NULL OR kid = ?) ORDER BY released_at DESC LIMIT 1"
      )
      .use {
        it.setString(1, binaryId)
        it.setString(2, kid)
        it.setString(3, kid)
        it.executeQuery().use { row ->
          if (row.next())
            Release(
              row.getString(1),
              row.getString(2),
              row.getString(3),
              row.getString(4),
              row.getString(5),
              row.getString(6),
              row.getTimestamp(7).toInstant(),
            )
          else null
        }
      }
  }

  private fun connect(): Connection = DriverManager.getConnection(jdbcUrl)

  private suspend fun <T> sql(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) { connect().use(block) }
}
