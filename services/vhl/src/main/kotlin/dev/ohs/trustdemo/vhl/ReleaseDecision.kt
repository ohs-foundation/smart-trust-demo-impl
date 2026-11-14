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

/** A clinic whose signed request passed verification: its certificate's CN and hcert kid. */
data class Requester(val name: String, val kid: String)

/** What a verified clinic asks to be released. */
sealed interface ReleaseRequest {
  val requester: Requester

  /** ITI-YY5: the manifest of List [listId], with the passcode the clinic sent, if any. */
  data class Manifest(
    override val requester: Requester,
    val listId: String,
    val recipient: String,
    val passcode: String?,
  ) : ReleaseRequest

  /** ITI-68: the Binary [binaryId] holding the encrypted document. */
  data class Document(override val requester: Requester, val binaryId: String) : ReleaseRequest
}

/** The answer to a [ReleaseRequest]; granted outcomes join this type with the release rule. */
sealed interface ReleaseOutcome {
  data object Denied : ReleaseOutcome
}

/** Decides whether a verified clinic may have what it asked for. */
fun interface ReleaseDecision {
  suspend fun decide(request: ReleaseRequest): ReleaseOutcome
}

/** Releases nothing. */
val DenyRelease = ReleaseDecision { ReleaseOutcome.Denied }
