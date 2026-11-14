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
package dev.ohs.trustdemo.onesa.data.hcert

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.ohs.trustdemo.hcert.TrustListSnapshot
import dev.ohs.trustdemo.hcert.TrustListStorage
import kotlinx.coroutines.flow.first

internal const val TRUST_LIST_DATASTORE_FILE_NAME = "trust_list.preferences_pb"

/**
 * Its own DataStore file, apart from the sync timestamps: a new sign-in clears those, but a trust
 * list belongs to the device.
 */
internal expect fun createTrustListDataStore(): DataStore<Preferences>

/** The last good fetch of a trust list, kept in Preferences. */
internal class DataStoreTrustListStorage(private val dataStore: DataStore<Preferences>) :
  TrustListStorage {
  override suspend fun read(): TrustListSnapshot? {
    val preferences = dataStore.data.first()
    val json = preferences[JSON] ?: return null
    return TrustListSnapshot(
      json = json,
      source = preferences[SOURCE].orEmpty(),
      fetchedAtEpochSeconds = preferences[FETCHED_AT] ?: 0,
    )
  }

  override suspend fun write(snapshot: TrustListSnapshot) {
    dataStore.edit {
      it[JSON] = snapshot.json
      it[SOURCE] = snapshot.source
      it[FETCHED_AT] = snapshot.fetchedAtEpochSeconds
    }
  }

  private companion object {
    val JSON = stringPreferencesKey("trust_list_json")
    val SOURCE = stringPreferencesKey("trust_list_source")
    val FETCHED_AT = longPreferencesKey("trust_list_fetched_at")
  }
}
