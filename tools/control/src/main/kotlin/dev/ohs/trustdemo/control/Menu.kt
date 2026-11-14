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

/** What the menu offers, each entry running an existing command. */
enum class MenuEntry(val label: String) {
  MAP("Journey map"),
  CURRENT("Current step"),
  QR("Show a test QR"),
  INSPECT("Inspect"),
  WATCH("Watch"),
  MOVE("Move time"),
  RESET("Reset"),
}

private val MOVES =
  mapOf(
    "Dry run: show the move" to listOf("time", "move", "J4.0", "--dry-run"),
    "Move to J4.0" to listOf("time", "move", "J4.0"),
    "Move to J4.0 with the year's doses" to listOf("time", "move", "J4.0", "--with-year-doses"),
  )

/**
 * The arrow-key menu shown when `trust-demo` runs alone. [choose] picks one of the options (null to
 * leave), [confirm] asks a yes or no, and [run] runs a command line as typed.
 */
class Menu(
  private val choose: (title: String, options: List<String>) -> String?,
  private val confirm: (question: String) -> Boolean,
  private val run: (List<String>) -> Unit,
) {
  fun loop() {
    while (true) {
      val label = choose("trust-demo", MenuEntry.entries.map { it.label }) ?: return
      dispatch(MenuEntry.entries.first { it.label == label })
    }
  }

  fun dispatch(entry: MenuEntry) {
    when (entry) {
      MenuEntry.MAP -> run(listOf("journeys"))
      MenuEntry.CURRENT -> run(listOf("status", "--current"))
      MenuEntry.QR -> choose("Which card", CARDS.toList())?.let { run(listOf("qr", it)) }
      MenuEntry.INSPECT -> run(listOf("inspect"))
      MenuEntry.WATCH -> run(listOf("watch"))
      MenuEntry.MOVE -> choose("Move time", MOVES.keys.toList())?.let { run(MOVES.getValue(it)) }
      MenuEntry.RESET ->
        if (confirm("Reset both servers to the story's start?")) run(listOf("reset"))
    }
  }
}
