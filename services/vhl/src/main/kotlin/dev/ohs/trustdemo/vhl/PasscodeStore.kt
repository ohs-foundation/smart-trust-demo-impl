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

/** Where a link's passcode is kept; called once per link, before anything is written to HAPI. */
fun interface PasscodeStore {
  suspend fun keep(consentId: String, passcode: String)
}

/** Keeps nothing: links are issued but nothing yet checks a passcode. */
val DiscardPasscodes = PasscodeStore { _, _ -> }
