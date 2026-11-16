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
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val POLL = 30.seconds

/**
 * Follows the story: a delivery at `POST /hook` and a poll every 30 seconds, for the Medplum-side
 * steps no HAPI Subscription sees, each re-read the step and print its line when it changes.
 */
class Watch(
  private val port: Int,
  private val states: StoryStates,
  private val print: (String) -> Unit = ::println,
  private val render: (StoryState) -> String = { "" },
) {
  private val lock = Mutex()
  private var last: String? = null

  fun install(app: Application) {
    app.routing {
      post("/hook") {
        runCatching { refresh() }
          .onSuccess { call.respond(HttpStatusCode.OK) }
          .onFailure {
            if (it is CancellationException) throw it
            call.respond(HttpStatusCode.InternalServerError, it.message.orEmpty())
          }
      }
    }
    app.launch {
      while (isActive) {
        runCatching { refresh() }
          .onFailure {
            if (it is CancellationException) throw it
            report("cannot read the story: ${it.message}")
          }
        delay(POLL)
      }
    }
  }

  fun serve() {
    // CIO reports a taken port only as an uncaught stack trace, after the poll has started.
    try {
      ServerSocket().use {
        it.reuseAddress = true
        it.bind(InetSocketAddress(port))
      }
    } catch (_: BindException) {
      throw CliktError(
        "Port $port is in use: another `trust-demo watch` is already running on this host. " +
          "Stop it first."
      )
    }
    embeddedServer(
        CIO,
        configure = {
          connector { port = this@Watch.port }
          reuseAddress = true
        },
      ) {
        install(this)
      }
      .start(wait = true)
  }

  // The read is inside the lock too, so a slow poll cannot print an older step after a delivery.
  private suspend fun refresh() =
    lock.withLock { states.state().let { state -> show(line(state), render(state)) } }

  private suspend fun report(line: String) = lock.withLock { show(line) }

  private fun show(line: String, map: String = "") {
    val shown = listOf(line, map).filter { it.isNotEmpty() }.joinToString("\n")
    if (shown != last) {
      last = shown
      print("${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)}  $shown")
    }
  }

  private fun line(state: StoryState): String {
    val step = StepEngine.currentStep(state) ?: return "All steps are done."
    val total = state.expectations.getValue(step).size
    return "${step.code} ${step.title}: ${statusLine(state, step)}, " +
      "${state.metCount(step)} of $total written"
  }
}
