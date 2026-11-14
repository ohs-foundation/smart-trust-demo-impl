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

import dev.ohs.trustdemo.onesa.auth.createKSafe
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * A Verifiable Health Link the wallet issued for one person. The [link] text holds the key that
 * decrypts the shared summary, so it is kept only in the encrypted store and never logged.
 */
@Serializable
data class IssuedLink(
  val consentId: String,
  val personId: String,
  val name: String,
  val link: String,
  /** The last day the link works, as `yyyy-MM-dd`. */
  val expires: String,
) {
  override fun toString(): String = "IssuedLink(consent=$consentId, person=$personId)"
}

/** Where issued links are kept, by the id of the Consent behind each. */
interface LinkStore {
  suspend fun keep(links: List<IssuedLink>)

  suspend fun get(consentIds: List<String>): List<IssuedLink>

  /** Drops the link of a consent that was withdrawn. */
  suspend fun remove(consentId: String)

  /** Drops every link whose last day is before [today]. */
  suspend fun purgeExpired(today: LocalDate)

  /** Drops every link, on sign-out. */
  suspend fun clear()
}

@Serializable private data class KeptLinks(val links: List<IssuedLink> = emptyList())

/**
 * The links in [KSafe], which encrypts at rest with each platform's keystore, as the session is. A
 * plain `object` so that [dev.ohs.trustdemo.onesa.auth.SessionRepository] can clear it on every
 * sign-out path.
 */
internal object SecureLinkStore : LinkStore {
  private val ksafe: KSafe by lazy { createKSafe() }

  override suspend fun keep(links: List<IssuedLink>) {
    val kept = read().links.filterNot { old -> links.any { it.consentId == old.consentId } }
    ksafe.put(KEY, KeptLinks(kept + links))
  }

  override suspend fun get(consentIds: List<String>): List<IssuedLink> =
    read().links.filter { it.consentId in consentIds }

  override suspend fun remove(consentId: String) {
    ksafe.put(KEY, KeptLinks(read().links.filterNot { it.consentId == consentId }))
  }

  override suspend fun purgeExpired(today: LocalDate) {
    ksafe.put(KEY, KeptLinks(read().links.filter { LocalDate.parse(it.expires) >= today }))
  }

  override suspend fun clear() {
    ksafe.put(KEY, KeptLinks())
  }

  private suspend fun read(): KeptLinks = ksafe.get(KEY, KeptLinks())

  private const val KEY = "vhl.issued"
}
