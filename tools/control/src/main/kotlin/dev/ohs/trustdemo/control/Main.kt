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
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
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

private class Stack(env: StackEnv) {
  val hapiUrl = "http://127.0.0.1:${env.require("HAPI_HOST_PORT")}/fhir"
  val medplumUrl = "http://medplum.kesaro.${env.require("STACK_HOST")}:${env.require("PROXY_PORT")}"
  val onesa = FhirServer(hapiUrl, { null }, CIO.create())
  val kesaro =
    MedplumAdmin(
      medplumUrl,
      env.require("MEDPLUM_SUPER_ADMIN_CLIENT_ID"),
      env.require("MEDPLUM_SUPER_ADMIN_CLIENT_SECRET"),
      CIO.create(),
    )
}

abstract class StackCommand(name: String) : CliktCommand(name = name) {
  protected val stackEnv by
    option("--stack-env", help = "The stack's .env file")
      .path(mustExist = true)
      .default(Path("stack/.env"))
}

class ResetCommand : StackCommand("reset") {

  override fun help(context: Context) =
    "Put both countries' servers into the story's starting state."

  override fun run() = runBlocking {
    val env = StackEnv.load(stackEnv)
    val stack = Stack(env)
    val secrets = checkNotNull(stackEnv.toAbsolutePath().parent).resolve("taifafeki/secrets")
    Reset(stack.onesa, stack.kesaro, env.require("TRUSTDEMO_USER_PASSWORD"), secrets)
      .run(LocalDate.now())
    echo(
      "Reset HAPI at ${stack.hapiUrl} and Medplum at ${stack.medplumUrl}; clinic certificate in $secrets"
    )
  }
}

class StatusCommand : StackCommand("status") {
  override fun help(context: Context) = "Show the current story step, worked out from the records."

  override fun run() = runBlocking {
    val stack = Stack(StackEnv.load(stackEnv))
    echo(status(StepEngine(stack.onesa, stack.kesaro).state()))
  }
}

class InspectCommand : StackCommand("inspect") {
  private val target by argument(help = "A step: J1, J2, J3a, J3b, Year, J4.0 or J4.1").optional()
  private val json by option("--json", help = "Print the records in full").flag()

  override fun help(context: Context) = "Show the records behind the current or a named step."

  override fun run() = runBlocking {
    val step = target?.let { stepNamed(it) ?: throw UsageError("no step named $it") }
    val stack = Stack(StackEnv.load(stackEnv))
    echo(inspect(StepEngine(stack.onesa, stack.kesaro).state(), step, json))
  }
}

class TimeCommand : CliktCommand(name = "time") {
  override fun help(context: Context) = "Move the story's clock."

  override fun run() = Unit
}

class MoveCommand : StackCommand("move") {
  private val target by argument(help = "The only supported move: J4.0")
  private val withYearDoses by
    option("--with-year-doses", help = "Also write the year's Onesan doses").flag()
  private val dryRun by option("--dry-run", help = "Print the move and write nothing").flag()

  override fun help(context: Context) = "Shift every story date so J4.0 falls today."

  override fun run() = runBlocking {
    if (target != "J4.0") throw UsageError("only the move to J4.0 is supported, not $target")
    val stack = Stack(StackEnv.load(stackEnv))
    val move = TimeMove(StepEngine(stack.onesa, stack.kesaro), stack.onesa, stack.kesaro)
    try {
      val plan = move.plan(withYearDoses)
      echo(plan.describe())
      if (dryRun) echo("Dry run: nothing written.")
      else {
        move.apply(plan)
        echo("Moved ${plan.changes.size} dates; wrote ${plan.yearDoses.size} year doses.")
      }
    } catch (refused: MoveRefused) {
      throw CliktError(refused.message)
    }
  }
}

class ExpireConsentsCommand : StackCommand("expire-consents") {
  override fun help(context: Context) = "End the current consents a minute ago."

  override fun run() = runBlocking {
    val stack = Stack(StackEnv.load(stackEnv))
    val move = TimeMove(StepEngine(stack.onesa, stack.kesaro), stack.onesa, stack.kesaro)
    val ended = move.expireConsents()
    echo(
      if (ended.isEmpty()) "No active consents to end."
      else "Ended ${ended.size} consent(s): ${ended.joinToString(", ")}"
    )
  }
}

fun main(args: Array<String>) =
  TrustDemo()
    .subcommands(
      ResetCommand(),
      StatusCommand(),
      InspectCommand(),
      TimeCommand().subcommands(MoveCommand(), ExpireConsentsCommand()),
    )
    .main(args)
