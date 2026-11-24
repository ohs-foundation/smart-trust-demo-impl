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

import com.github.ajalt.mordant.rendering.BorderType
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Padding
import com.github.ajalt.mordant.widgets.Panel
import com.github.ajalt.mordant.widgets.Text

private val TAIFAFEKI = setOf(App.EMR, App.VHL_VIEWER)
private val INDENT = mapOf(App.CHW_PHONE to 4, App.CLINIC_TABLET to 4, App.FAMILY_WALLET to 4)
private val HANDOVER =
  mapOf(
    App.EMR to "paper cards (DVC QR)",
    App.CHW_PHONE to "sync + referral",
    App.CLINIC_TABLET to "",
  )
private const val VHL_LINK = "VHL QR + passcode"
private const val TITLE_WIDTH = 30

/**
 * [title] broken after its colon and between words into lines of at most [TITLE_WIDTH], so the
 * boxes stay narrow.
 */
private fun wrapped(title: String): List<String> =
  title.split(Regex("(?<=:) ")).flatMap { part ->
    part.split(' ').fold(emptyList()) { lines, word ->
      val last = lines.lastOrNull()
      if (last != null && last.length + 1 + word.length <= TITLE_WIDTH) {
        lines.dropLast(1) + "$last $word"
      } else lines + word
    }
  }

/** [line] with its visible character at [column] replaced, skipping ANSI colour sequences. */
private fun withCharAt(line: String, column: Int, char: Char): String {
  var seen = 0
  var i = 0
  while (i < line.length) {
    if (line[i] == '\u001B') i = line.indexOf('m', i) + 1
    else if (seen++ == column) return line.substring(0, i) + char + line.substring(i + 1) else i++
  }
  return line
}

/** True when the terminal's output encoding can carry the map's boxes, arrows and emoji. */
fun supportsUnicode(): Boolean =
  System.getProperty("stdout.encoding")?.contains("UTF", ignoreCase = true) ?: true

/**
 * Draws each app as a box holding its journeys with their standing, joined by arrows naming what
 * passes between them. The current step's box is marked and bold; without colour or Unicode the map
 * falls back to ASCII boxes and words.
 */
fun journeyMap(
  state: StoryState,
  terminal: Terminal = Terminal(),
  unicode: Boolean = supportsUnicode(),
): String {
  val current = StepEngine.currentStep(state)
  val codeWidth = Step.entries.maxOf { it.code.length }
  val titleWidth = Step.entries.flatMap { wrapped(it.title) }.maxOf { it.length }
  fun mark(step: Step) =
    when {
      state.isDone(step) -> if (unicode) TextColors.green("✅") else TextColors.green("done       ")
      step == current || state.metCount(step) > 0 ->
        if (unicode) TextColors.yellow("🔄") else TextColors.yellow("in progress")
      else -> if (unicode) TextStyles.dim("⬜") else TextStyles.dim("to do      ")
    }
  fun rows(step: Step): List<String> {
    val title = wrapped(step.title)
    return listOf(
      "${step.code.padEnd(codeWidth)}  ${title.first().padEnd(titleWidth)}  ${mark(step)} " +
        "${state.metCount(step)}/${state.expectations.getValue(step).size}"
    ) + title.drop(1).map { " ".repeat(codeWidth + 2) + it }
  }

  fun width(text: String) =
    Text(text, whitespace = Whitespace.PRE).measure(terminal, MEASURE_WIDTH).max
  fun titleOf(app: App) =
    "${if (app == current?.app) (if (unicode) "▶ " else "> ") else ""}${app.label} · ${app.driver}"
  fun lines(app: App) = Step.entries.filter { it.app == app }.flatMap(::rows)

  val inner = App.entries.maxOf { app -> maxOf(width(titleOf(app)) + 2, lines(app).maxOf(::width)) }
  val boxWidth = inner + 4

  fun panel(app: App): Panel {
    val here = app == current?.app
    val tone = if (app in TAIFAFEKI) TextColors.blue else TextColors.magenta
    val title = titleOf(app)
    val body = lines(app).joinToString("\n") { it + " ".repeat(inner - width(it)) }
    return Panel(
      Text(body, whitespace = Whitespace.PRE),
      title = Text(if (here) TextStyles.bold(title) else title),
      borderType = if (unicode) BorderType.ROUNDED else BorderType.ASCII,
      borderStyle = if (here) TextColors.yellow + TextStyles.bold else tone,
      padding = Padding(0, 1, 0, 1),
    )
  }

  val down = if (unicode) "▼" else "v"
  val bar = if (unicode) "│" else "|"
  fun render(text: String) = terminal.render(Text(text, whitespace = Whitespace.PRE))

  return buildString {
      appendLine(
        render(
          "${TextColors.blue("Taifafeki · facility client")}   ${TextColors.magenta("Onesa · Onesa app")}"
        )
      )
      App.entries.forEach { app ->
        val indent = INDENT[app] ?: 0
        val lines = terminal.render(panel(app)).lines()
        val linkRow = 1
        when (app) {
          App.FAMILY_WALLET -> {
            lines.dropLast(1).forEach { appendLine(" ".repeat(indent) + it) }
            val bottom = withCharAt(lines.last(), boxWidth - indent, if (unicode) '┬' else '+')
            appendLine(" ".repeat(indent) + bottom)
            appendLine(render("${" ".repeat(boxWidth)}$bar $VHL_LINK"))
          }
          App.VHL_VIEWER ->
            lines.forEachIndexed { row, it ->
              appendLine(
                it +
                  when {
                    row < linkRow -> bar
                    row == linkRow -> if (unicode) "◀╯" else "<-+"
                    else -> ""
                  }
              )
            }
          else -> lines.forEach { appendLine(" ".repeat(indent) + it) }
        }
        HANDOVER[app]?.let { what ->
          val stem = " ".repeat(indent + boxWidth / 2)
          appendLine(render("$stem$bar $what".trimEnd()))
          appendLine(render("$stem$down"))
        }
      }
    }
    .trimEnd()
}

private const val MEASURE_WIDTH = 200
