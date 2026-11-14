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

import co.touchlab.kermit.Logger
import dev.ohs.trustdemo.hcert.DidDocuments
import dev.ohs.trustdemo.hcert.HcertClock
import dev.ohs.trustdemo.hcert.HcertEngine
import dev.ohs.trustdemo.hcert.HcertTrustException
import dev.ohs.trustdemo.hcert.HcertTrustStore
import dev.ohs.trustdemo.hcert.TrustListCache
import dev.ohs.trustdemo.hcert.TrustListSnapshot
import dev.ohs.trustdemo.hcert.VerificationResult
import dev.ohs.trustdemo.hcert.loadHcertEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The trust domains of `CodeSystem/TrustDemoTrustDomain`: which kind of document a key may sign.
 */
enum class TrustDomain(val code: String) {
  Dvc("DEMO-DVC"),
  Vhl("DEMO-VHL"),
}

/** A trust list the app holds, and the pinned DID document of the publisher that signs it. */
class PinnedTrustList(
  val name: String,
  val signer: suspend () -> String,
  val cache: TrustListCache,
)

/** What the trust list status screen shows about one list. */
data class TrustListStatus(
  val name: String,
  val source: String,
  val signer: String,
  val fetchedAtEpochSeconds: Long?,
  val entryCount: Int,
  val domains: List<String>,
)

/**
 * The app's one door to the HCERT engine. Cards and links are checked against separate trust
 * stores, because the engine trusts every certificate a list holds whatever its domain: the
 * `DEMO-DVC` store holds the local list's `DEMO-DVC` entries and the GDHCN DEV snapshot, the
 * `DEMO-VHL` store the local list's `DEMO-VHL` entries only. Each list is verified against its
 * pinned signer before any of it is loaded, and the signers are never fetched.
 */
class HcertService(private val local: PinnedTrustList, private val gdhcnDev: PinnedTrustList) {
  private class Built(val store: HcertTrustStore, val from: List<TrustListSnapshot?>)

  private val lock = Mutex()
  private var engine: HcertEngine? = null
  private val built = mutableMapOf<TrustDomain, Built>()

  suspend fun verifyCard(
    hc1: String,
    now: Long = HcertClock.nowEpochSeconds(),
  ): VerificationResult = verify(TrustDomain.Dvc, hc1, now)

  suspend fun verifyLink(
    hc1: String,
    now: Long = HcertClock.nowEpochSeconds(),
  ): VerificationResult = verify(TrustDomain.Vhl, hc1, now)

  /** Fetches the local list; the copy already held stays when the fetch or its proof fails. */
  suspend fun refreshLocalList(
    now: Long = HcertClock.nowEpochSeconds()
  ): Result<TrustListSnapshot> {
    val engine = engine()
    val signer = local.signer()
    return local.cache.refresh(now) { engine.newTrustStore().addVerifiedDidDocument(it, signer) }
  }

  /** The sync hook: a failed refresh is logged, never surfaced as a sync failure. */
  suspend fun refreshLocalListQuietly() {
    refreshLocalList().onFailure {
      Logger.w(it) { "Trust list refresh failed; keeping the last one" }
    }
  }

  suspend fun statuses(): List<TrustListStatus> = listOf(local, gdhcnDev).map { it.status() }

  private suspend fun PinnedTrustList.status(): TrustListStatus {
    val snapshot = cache.current()
    return TrustListStatus(
      name = name,
      source = snapshot?.source.orEmpty(),
      signer = DidDocuments.id(signer()).orEmpty(),
      fetchedAtEpochSeconds = snapshot?.fetchedAtEpochSeconds,
      entryCount = snapshot?.let { DidDocuments.entryCount(it.json) } ?: 0,
      domains = snapshot?.let { DidDocuments.domains(it.json) }.orEmpty(),
    )
  }

  private suspend fun verify(domain: TrustDomain, hc1: String, now: Long): VerificationResult {
    val engine = engine()
    return engine.verify(hc1, storeFor(domain, engine), now)
  }

  private suspend fun engine(): HcertEngine =
    lock.withLock { engine ?: loadHcertEngine().also { engine = it } }

  private suspend fun storeFor(domain: TrustDomain, engine: HcertEngine): HcertTrustStore {
    val sources =
      when (domain) {
        TrustDomain.Dvc -> listOf(local to TrustDomain.Dvc.code, gdhcnDev to null)
        TrustDomain.Vhl -> listOf(local to TrustDomain.Vhl.code)
      }
    val snapshots = sources.map { (list, _) -> list.cache.current() }
    return lock.withLock {
      built[domain]?.takeIf { it.from == snapshots }?.store
        ?: engine.newTrustStore().also { store ->
          sources.zip(snapshots).forEach { (source, snapshot) ->
            val (list, restrictTo) = source
            snapshot?.let { store.addQuietly(list, it, restrictTo) }
          }
          built[domain] = Built(store, snapshots)
        }
    }
  }

  private suspend fun HcertTrustStore.addQuietly(
    list: PinnedTrustList,
    snapshot: TrustListSnapshot,
    restrictTo: String?,
  ) {
    try {
      addVerifiedDidDocument(snapshot.json, list.signer(), restrictTo)
    } catch (e: HcertTrustException) {
      Logger.w(e) { "${list.name} not loaded" }
    }
  }
}
