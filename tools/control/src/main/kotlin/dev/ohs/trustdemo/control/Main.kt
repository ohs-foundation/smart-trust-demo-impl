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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.engine.cio.CIO
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

class TrustDemo : CliktCommand(name = "trust-demo") {
  override fun help(context: Context) = "Drive the SMART Trust demo from outside the apps."

  override fun run() = Unit
}

class ResetCommand : CliktCommand(name = "reset") {
  private val hapi by
    option("--hapi", envvar = "TRUST_DEMO_HAPI", help = "Onesa HAPI base URL")
      .default("http://127.0.0.1:18082/fhir")

  override fun help(context: Context) = "Put Onesa's HAPI server into the story's starting state."

  override fun run() = runBlocking {
    Reset(FhirServer(hapi, { null }, CIO.create())).run(LocalDate.now())
    echo("HAPI reset to the base data at $hapi")
  }
}

fun main(args: Array<String>) = TrustDemo().subcommands(ResetCommand()).main(args)
