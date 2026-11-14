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

import kotlin.test.Test
import kotlin.test.assertEquals

private class Scripted(vararg scripted: String?, private val yes: Boolean = true) {
  private val picks = scripted.toMutableList()
  val ran = mutableListOf<List<String>>()
  val asked = mutableListOf<String>()
  val menu =
    Menu(
      choose = { _, _ -> picks.removeAt(0) },
      confirm = {
        asked += it
        yes
      },
      run = { ran += it },
    )
}

class MenuTest {
  @Test
  fun eachEntryRunsItsExistingCommand() {
    val menu = Scripted()
    val expected =
      mapOf(
        MenuEntry.MAP to listOf("journeys"),
        MenuEntry.CURRENT to listOf("status", "--current"),
        MenuEntry.INSPECT to listOf("inspect"),
        MenuEntry.WATCH to listOf("watch"),
        MenuEntry.RESET to listOf("reset"),
      )
    expected.forEach { (entry, args) ->
      menu.ran.clear()
      menu.menu.dispatch(entry)
      assertEquals(listOf(args), menu.ran, entry.name)
    }
  }

  @Test
  fun qrAndMoveRunTheChosenVariant() {
    val menu = Scripted("somsak-hepb", "Move to J4.0")
    menu.menu.dispatch(MenuEntry.QR)
    menu.menu.dispatch(MenuEntry.MOVE)
    assertEquals(listOf(listOf("qr", "somsak-hepb"), listOf("time", "move", "J4.0")), menu.ran)
  }

  @Test
  fun resetRunsOnlyAfterConfirming() {
    val declined = Scripted(yes = false)
    declined.menu.dispatch(MenuEntry.RESET)
    assertEquals(1, declined.asked.size)
    assertEquals(emptyList(), declined.ran)
  }

  @Test
  fun loopRunsPickedEntriesUntilTheUserLeaves() {
    val menu = Scripted("Journey map", "Current step", null)
    menu.menu.loop()
    assertEquals(listOf(listOf("journeys"), listOf("status", "--current")), menu.ran)
  }
}
