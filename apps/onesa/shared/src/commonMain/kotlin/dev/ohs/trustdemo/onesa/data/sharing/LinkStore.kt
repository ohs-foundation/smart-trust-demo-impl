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

import dev.ohs.trustdemo.onesa.auth.SessionRepository
import dev.ohs.trustdemo.onesa.auth.createKSafe
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.serialization.Serializable

/**
 * A Verifiable Health Link the wallet issued for one person. The [link] text holds the key that
 * decrypts the shared summary, and [passcode] is what the holder says to the clinician, so both are
 * kept only in the encrypted store and never logged.
 */
@Serializable
data class IssuedLink(
  val consentId: String,
  val personId: String,
  val name: String,
  val link: String,
  /** When the link stops working, in epoch seconds, as the link itself says. */
  val exp: Long = 0,
  /** The share's passcode; null for a link kept before the wallet kept passcodes. */
  val passcode: String? = null,
) {
  override fun toString(): String = "IssuedLink(consent=$consentId, person=$personId)"
}

/** Where issued links and their passcodes are kept, by the id of the Consent behind each. */
interface LinkStore {
  suspend fun keep(links: List<IssuedLink>)

  suspend fun get(consentIds: List<String>): List<IssuedLink>

  /** Drops the link of a consent that was withdrawn. */
  suspend fun remove(consentId: String)

  /** Drops every link that has expired by [nowEpochSeconds]. */
  suspend fun purgeExpired(nowEpochSeconds: Long)

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
    if (SessionRepository.session.value == null) return
    val kept = read().links.filterNot { old -> links.any { it.consentId == old.consentId } }
    ksafe.put(KEY, KeptLinks(kept + links))
  }

  override suspend fun get(consentIds: List<String>): List<IssuedLink> =
    read().links.filter { it.consentId in consentIds }

  override suspend fun remove(consentId: String) {
    ksafe.put(KEY, KeptLinks(read().links.filterNot { it.consentId == consentId }))
  }

  override suspend fun purgeExpired(nowEpochSeconds: Long) {
    if (ksafe.get(CLEARING, false)) clear()
    ksafe.put(KEY, KeptLinks(read().links.filter { it.exp > nowEpochSeconds }))
  }

  /** A clear that fails is marked, and [purgeExpired] finishes it at the next start. */
  override suspend fun clear() {
    ksafe.put(CLEARING, true)
    ksafe.put(KEY, KeptLinks())
    ksafe.put(CLEARING, false)
  }

  private suspend fun read(): KeptLinks = ksafe.get(KEY, KeptLinks())

  private const val KEY = "vhl.issued"
  private const val CLEARING = "vhl.clearing"
}
