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

import dev.ohs.trustdemo.hcert.TrustListCache
import dev.ohs.trustdemo.hcert.TrustListEndpoints
import dev.ohs.trustdemo.hcert.TrustListStorage
import dev.ohs.trustdemo.onesa.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Trust material shipped with the app: the signer documents the lists are verified against, and the
 * GDHCN DEV snapshot, so a device verifies cards before its first sync.
 */
object BundledTrustLists {
  /** When the bundled snapshot was fetched from tng-cdn-dev.who.int: 2026-10-23T07:58Z. */
  const val GDHCN_DEV_FETCHED_AT_EPOCH_SECONDS = 1_792_742_280L

  suspend fun localSigner(): String = read("local-list-signer.json")

  suspend fun gdhcnDevSigner(): String = read("gdhcn-dev-signer.json")

  /** The GDHCN DEV list, never fetched: [storage] only holds what a refresh would have kept. */
  fun gdhcnDev(storage: TrustListStorage): PinnedTrustList =
    PinnedTrustList(
      name = "GDHCN DEV snapshot",
      signer = ::gdhcnDevSigner,
      cache =
        TrustListCache(
          storage,
          url = TrustListEndpoints.DEV,
          bundled = { read("gdhcn-dev-trustlist.json") },
          bundledFetchedAtEpochSeconds = GDHCN_DEV_FETCHED_AT_EPOCH_SECONDS,
        ),
    )

  @OptIn(ExperimentalResourceApi::class)
  private suspend fun read(name: String): String =
    Res.readBytes("files/trust/$name").decodeToString()
}
