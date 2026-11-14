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
package dev.ohs.trustdemo.onesa.feature.trustlist

import kotlinx.serialization.Serializable

/** One key of a trust list as the status screen shows it; [testOnly] for the demo's domains. */
@Serializable
data class TrustKeyState(
  val participant: String? = null,
  val usage: String? = null,
  val domain: String? = null,
  val kid: String? = null,
  val testOnly: Boolean = false,
)

/**
 * One trust list as the status screen shows it. A [bundled] list ships with the app and is never
 * fetched; [signingKeys] counts its document signing keys, of which [namedKeys] lists those of the
 * demo's countries, and [keys] holds every key.
 */
@Serializable
data class TrustListState(
  val name: String,
  val source: String? = null,
  val signer: String? = null,
  val fetchedAt: String? = null,
  val fetchedAtEpochSeconds: Long? = null,
  val entryCount: String? = null,
  val domains: String? = null,
  val stale: Boolean = false,
  val refreshFailedAt: String? = null,
  val refreshFailure: String? = null,
  val bundled: Boolean = false,
  val signingKeys: Int = 0,
  val namedKeys: String? = null,
  val keys: List<TrustKeyState> = emptyList(),
)
