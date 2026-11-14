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
import dev.ohs.trustdemo.hcert.ReadResult
import dev.ohs.trustdemo.hcert.TrustListCache
import dev.ohs.trustdemo.hcert.TrustListSnapshot
import dev.ohs.trustdemo.hcert.TrustedDscInfo
import dev.ohs.trustdemo.hcert.VerificationResult
import dev.ohs.trustdemo.hcert.loadHcertEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

/**
 * A fetched list older than this is shown as stale; verification still runs against it. The bundled
 * GDHCN snapshot is never fetched, so it shows its date and is never stale.
 */
const val TRUST_LIST_STALE_AFTER_SECONDS = 24 * 60 * 60L

/** When the latest refresh of a list failed, and why; the last good list stays in use. */
data class RefreshFailure(val atEpochSeconds: Long, val reason: String)

/** When the list behind a verification was last fetched, and whether that is over a day ago. */
data class ListFreshness(val fetchedAtEpochSeconds: Long?, val stale: Boolean)

private fun freshnessOf(fetchedAtEpochSeconds: Long?, now: Long, fetched: Boolean) =
  ListFreshness(
    fetchedAtEpochSeconds,
    stale =
      fetched &&
        fetchedAtEpochSeconds != null &&
        now - fetchedAtEpochSeconds > TRUST_LIST_STALE_AFTER_SECONDS,
  )

/**
 * What the trust list status screen shows about one list; a [bundled] list ships with the app and
 * is never fetched.
 */
data class TrustListStatus(
  val name: String,
  val source: String,
  val signer: String,
  val fetchedAtEpochSeconds: Long?,
  val entryCount: Int,
  val domains: List<String>,
  val stale: Boolean = false,
  val lastFailure: RefreshFailure? = null,
  val entries: List<TrustEntry> = emptyList(),
  val bundled: Boolean = false,
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
  private val failures = MutableStateFlow<Map<String, RefreshFailure>>(emptyMap())

  suspend fun verifyCard(
    hc1: String,
    now: Long = HcertClock.nowEpochSeconds(),
  ): VerificationResult = verify(TrustDomain.Dvc, hc1, now)

  /** Where the list that vouched for [dsc] came from: its URL, for a record's provenance. */
  suspend fun trustListSource(dsc: TrustedDscInfo): String =
    (if (dsc.domain == TrustDomain.Dvc.code) local else gdhcnDev).cache.current()?.source.orEmpty()

  suspend fun verifyLink(
    hc1: String,
    now: Long = HcertClock.nowEpochSeconds(),
  ): VerificationResult = verify(TrustDomain.Vhl, hc1, now)

  /**
   * Decodes a card without a trust lookup or a signature check: for a visit where nothing can be
   * verified.
   */
  suspend fun read(hc1: String): ReadResult = engine().readUnverified(hc1)

  /** How fresh the list that vouched for [dsc] is. */
  suspend fun freshness(
    dsc: TrustedDscInfo,
    now: Long = HcertClock.nowEpochSeconds(),
  ): ListFreshness {
    val list = if (dsc.domain == TrustDomain.Dvc.code) local else gdhcnDev
    return freshnessOf(list.cache.current()?.fetchedAtEpochSeconds, now, fetched = list === local)
  }

  /**
   * Fetches the local list; the copy already held stays when the fetch or its proof fails, and the
   * failure is kept for [statuses] until a fetch succeeds.
   */
  suspend fun refreshLocalList(
    now: Long = HcertClock.nowEpochSeconds()
  ): Result<TrustListSnapshot> {
    val engine = engine()
    val signer = local.signer()
    return local.cache
      .refresh(now) { engine.newTrustStore().addVerifiedDidDocument(it, signer) }
      .also { result ->
        failures.update { current ->
          result.exceptionOrNull()?.let {
            current +
              (local.name to RefreshFailure(now, it.message ?: it::class.simpleName.orEmpty()))
          } ?: (current - local.name)
        }
      }
  }

  /** The sync hook: a failed refresh is logged, never surfaced as a sync failure. */
  suspend fun refreshLocalListQuietly() {
    refreshLocalList().onFailure {
      Logger.w(it) { "Trust list refresh failed; keeping the last one" }
    }
  }

  suspend fun statuses(now: Long = HcertClock.nowEpochSeconds()): List<TrustListStatus> =
    listOf(local, gdhcnDev).map { it.status(now) }

  private suspend fun PinnedTrustList.status(now: Long): TrustListStatus {
    val snapshot = cache.current()
    return TrustListStatus(
      name = name,
      source = snapshot?.source.orEmpty(),
      signer = DidDocuments.id(signer()).orEmpty(),
      fetchedAtEpochSeconds = snapshot?.fetchedAtEpochSeconds,
      entryCount = snapshot?.let { DidDocuments.entryCount(it.json) } ?: 0,
      domains = snapshot?.let { DidDocuments.domains(it.json) }.orEmpty(),
      stale = freshnessOf(snapshot?.fetchedAtEpochSeconds, now, fetched = this === local).stale,
      lastFailure = failures.value[name],
      entries = snapshot?.let { trustEntries(it.json) }.orEmpty(),
      bundled = this !== local,
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
