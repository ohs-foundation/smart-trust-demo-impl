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
import com.github.ajalt.clikt.parameters.types.path
import io.ktor.client.engine.cio.CIO
import java.time.LocalDate
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking

class TrustDemo : CliktCommand(name = "trust-demo") {
  override fun help(context: Context) = "Drive the SMART Trust demo from outside the apps."

  override fun run() = Unit
}

class ResetCommand : CliktCommand(name = "reset") {
  private val stackEnv by
    option("--stack-env", help = "The stack's .env file")
      .path(mustExist = true)
      .default(Path("stack/.env"))

  override fun help(context: Context) =
    "Put both countries' servers into the story's starting state."

  override fun run() = runBlocking {
    val env = StackEnv.load(stackEnv)
    val hapi = "http://127.0.0.1:${env.require("HAPI_HOST_PORT")}/fhir"
    val medplum = "http://medplum.kesaro.${env.require("STACK_HOST")}:${env.require("PROXY_PORT")}"
    val kesaro =
      MedplumAdmin(
        medplum,
        env.require("MEDPLUM_SUPER_ADMIN_CLIENT_ID"),
        env.require("MEDPLUM_SUPER_ADMIN_CLIENT_SECRET"),
        CIO.create(),
      )
    val secrets = checkNotNull(stackEnv.toAbsolutePath().parent).resolve("taifafeki/secrets")
    Reset(
        FhirServer(hapi, { null }, CIO.create()),
        kesaro,
        env.require("TRUSTDEMO_USER_PASSWORD"),
        secrets,
      )
      .run(LocalDate.now())
    echo("Reset HAPI at $hapi and Medplum at $medplum; clinic certificate in $secrets")
  }
}

fun main(args: Array<String>) = TrustDemo().subcommands(ResetCommand()).main(args)
