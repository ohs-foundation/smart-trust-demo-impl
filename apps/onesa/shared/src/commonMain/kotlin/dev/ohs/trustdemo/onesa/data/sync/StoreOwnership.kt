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
package dev.ohs.trustdemo.onesa.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.trustdemo.onesa.data.DataChangeSignal
import dev.ohs.trustdemo.onesa.data.sharing.LinkStore
import kotlinx.coroutines.flow.first

internal val STORE_OWNER_KEY = stringPreferencesKey("store_owner")

/** Whether the user signing in may use the records this phone holds. */
sealed interface Admission {
  data object Allowed : Admission

  data class Blocked(val message: String) : Admission
}

/**
 * Remembers whose records the local store holds. Signing out keeps them, so the same user can work
 * offline; a different user starts from an empty store, and is refused while the store still holds
 * work that was never uploaded, which is never dropped silently.
 */
internal class StoreOwnership(
  private val engine: FhirEngine,
  private val state: DataStore<Preferences>,
  private val links: LinkStore,
) {
  /** Hands the store to [subject], emptying it first when it held another user's records. */
  suspend fun admit(subject: String): Admission {
    if (state.data.first()[STORE_OWNER_KEY] == subject) return Admission.Allowed
    if (hasUnsyncedChanges()) return Admission.Blocked(UNSYNCED)
    engine.clearDatabase()
    state.edit {
      it.clear()
      it[STORE_OWNER_KEY] = subject
    }
    links.clear()
    DataChangeSignal.notifyChanged()
    return Admission.Allowed
  }

  private suspend fun hasUnsyncedChanges(): Boolean =
    ResourceType.entries.any { type ->
      engine.count(Search(type)) > 0 &&
        engine.search<Resource>(Search(type)).any {
          engine.getLocalChanges(type, it.resource.id.orEmpty()).isNotEmpty()
        }
    }

  private companion object {
    const val UNSYNCED =
      "This phone holds another user's unsynced work. They must sign in and sync it first."
  }
}
