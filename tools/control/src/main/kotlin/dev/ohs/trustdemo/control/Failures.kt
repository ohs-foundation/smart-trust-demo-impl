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
package dev.ohs.trustdemo.control

import com.github.ajalt.clikt.core.CliktError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.net.ConnectException
import java.net.UnknownHostException

/** Runs [block], turning a refused or unresolvable connection to [url] into a plain error. */
suspend fun <T> reaching(url: String, block: suspend () -> T): T =
  try {
    block()
  } catch (_: ConnectException) {
    throw unreachable(url)
  } catch (_: UnknownHostException) {
    throw unreachable(url)
  }

private fun unreachable(url: String) =
  CliktError("Cannot reach $url — is the stack up? (make stack-up)")

fun refused(method: HttpMethod, url: String, status: HttpStatusCode): Nothing =
  throw CliktError("$method $url answered $status")
