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
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.input.interactiveSelectList
import com.github.ajalt.mordant.terminal.YesNoPrompt
import io.ktor.client.engine.cio.CIO
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking

class TrustDemo : CliktCommand(name = "trust-demo") {
  override val invokeWithoutSubcommand = true

  override fun help(context: Context) = "Drive the SMART Trust demo from outside the apps."

  override fun run() {
    if (currentContext.invokedSubcommand != null) return
    val terminal = currentContext.terminal
    if (!terminal.terminalInfo.interactive) throw PrintHelpMessage(currentContext, error = true)
    Menu(
        choose = { title, options ->
          terminal.interactiveSelectList {
            title(title)
            entries(*options.toTypedArray())
            clearOnExit(true)
          }
        },
        confirm = { YesNoPrompt(it, terminal, default = false).ask() == true },
        run = { args ->
          try {
            trustDemo().parse(args)
          } catch (failed: CliktError) {
            echo(failed.message, err = true)
          }
        },
      )
      .loop()
  }
}

private class Stack(env: StackEnv, envFile: Path) {
  private val urls = StackUrls.from(env, envFile)
  val hapiUrl = urls.hapi
  val medplumUrl = urls.medplum
  val clientOrigin = urls.clientOrigin
  val secretsDir = urls.secretsDir
  val watchPort = urls.watchPort
  val onesa = FhirServer(hapiUrl, { null }, serverEngine())
  val kesaro =
    MedplumAdmin(
      medplumUrl,
      env.require("MEDPLUM_SUPER_ADMIN_CLIENT_ID"),
      env.require("MEDPLUM_SUPER_ADMIN_CLIENT_SECRET"),
      serverEngine(),
    )
}

// Seeding a freshly started server takes longer than CIO's 15-second default request timeout.
internal fun serverEngine() = CIO.create { requestTimeout = 300_000 }

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
    val stack = Stack(env, stackEnv)
    val secrets = stack.secretsDir
    Reset(
        stack.onesa,
        stack.kesaro,
        env.require("TRUSTDEMO_USER_PASSWORD"),
        secrets,
        stack.watchPort,
        stack.clientOrigin,
      )
      .run(LocalDate.now())
    echo(
      "Reset HAPI at ${stack.hapiUrl} and Medplum at ${stack.medplumUrl}; clinic certificate in $secrets"
    )
  }
}

class StatusCommand : StackCommand("status") {
  private val current by option("--current", help = "Print only the current step").flag()

  override fun help(context: Context) = "Show the current story step, worked out from the records."

  override fun run() = runBlocking {
    val stack = Stack(StackEnv.load(stackEnv), stackEnv)
    val state = StepEngine(stack.onesa, stack.kesaro).state()
    if (current) echo(currentStep(state))
    else {
      echo(journeyMap(state, currentContext.terminal))
      echo()
      echo(status(state))
    }
  }
}

class JourneysCommand : StackCommand("journeys") {
  override fun help(context: Context) =
    "Draw the journeys by app, with their standing from the records."

  override fun run() = runBlocking {
    val stack = Stack(StackEnv.load(stackEnv), stackEnv)
    echo(journeyMap(StepEngine(stack.onesa, stack.kesaro).state(), currentContext.terminal))
  }
}

class WatchCommand : StackCommand("watch") {
  override fun help(context: Context) = "Follow the story live from HAPI's webhook and a poll."

  override fun run() {
    val stack = Stack(StackEnv.load(stackEnv), stackEnv)
    echo("Watching on port ${stack.watchPort}; Ctrl-C stops.")
    val terminal = currentContext.terminal
    Watch(
        stack.watchPort,
        StepEngine(stack.onesa, stack.kesaro),
        render = { journeyMap(it, terminal) },
      )
      .serve()
  }
}

class InspectCommand : StackCommand("inspect") {
  private val target by argument(help = "A step: J1, J2, J3a, J3b, Year, J4.0 or J4.1").optional()
  private val json by option("--json", help = "Print the records in full").flag()

  override fun help(context: Context) = "Show the records behind the current or a named step."

  override fun run() = runBlocking {
    val step = target?.let { stepNamed(it) ?: throw UsageError("no step named $it") }
    val stack = Stack(StackEnv.load(stackEnv), stackEnv)
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
    val stack = Stack(StackEnv.load(stackEnv), stackEnv)
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
    val stack = Stack(StackEnv.load(stackEnv), stackEnv)
    val move = TimeMove(StepEngine(stack.onesa, stack.kesaro), stack.onesa, stack.kesaro)
    val ended = move.expireConsents()
    echo(
      if (ended.isEmpty()) "No active consents to end."
      else "Ended ${ended.size} consent(s): ${ended.joinToString(", ")}"
    )
  }
}

fun trustDemo() =
  TrustDemo()
    .subcommands(
      ResetCommand(),
      StatusCommand(),
      JourneysCommand(),
      WatchCommand(),
      QrCommand(),
      InspectCommand(),
      TimeCommand().subcommands(MoveCommand(), ExpireConsentsCommand()),
    )

fun main(args: Array<String>) = trustDemo().main(args)
