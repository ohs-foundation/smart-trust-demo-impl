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

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun plainTerminal() = Terminal(ansiLevel = AnsiLevel.NONE, width = 100)

private fun map(fixture: String, unicode: Boolean = true) =
  journeyMap(fixtureState("steps/$fixture.json"), plainTerminal(), unicode)

class JourneyMapTest {
  @Test
  fun everyJourneyAppearsInItsAppsBoxWithItsCount() {
    val boxes = map("before-J2").split("╭").drop(1)
    assertEquals(5, boxes.size)
    Step.entries.forEach { step ->
      val total = expectations.getValue(step).size
      val box = boxes[App.entries.indexOf(step.app)]
      assertContains(box, "${step.code} ")
      step.title.split(' ').forEach { assertContains(box, it) }
      assertContains(box, "0/$total".takeIf { step != Step.J1 } ?: "2/$total")
    }
    assertContains(boxes[0], "Hospital EMR · Dr Anan")
    assertContains(boxes[2], "Clinic tablet · Nurse Chanthason")
  }

  @Test
  fun standingShowsDoneInProgressAndToDo() {
    val lines = map("before-J2").lines()
    assertTrue(lines.single { "J1 " in it }.contains("✅"))
    assertTrue(lines.single { "J2 " in it }.contains("🔄"))
    assertTrue(lines.single { "J3a " in it }.contains("⬜"))
  }

  @Test
  fun onlyTheCurrentStepsBoxIsMarked() {
    val marked = map("before-J2").lines().filter { "▶" in it }
    assertEquals(1, marked.size)
    assertContains(marked.single(), "CHW phone")
    assertFalse("▶" in map("after-J41"))
  }

  @Test
  fun arrowsNameWhatPassesBetweenBoxes() {
    val text = map("before-J2")
    listOf("paper cards (DVC QR)", "sync + referral", "VHL QR + passcode").forEach {
      assertContains(text, it)
    }
  }

  @Test
  fun theVhlLinkBendsBackIntoTheViewerBox() {
    val lines = map("before-J2").lines()
    val label = lines.indexOfFirst { "VHL QR + passcode" in it }
    val top = lines.indexOfFirst { "VHL viewer" in it }
    assertEquals(label + 1, top)
    assertTrue(lines[top].endsWith("╮│"), lines[top])
    assertTrue(lines[top + 1].endsWith("│◀╯"), lines[top + 1])
    assertEquals(lines[label].indexOf('│'), lines[top].lastIndexOf('│'))
    assertTrue("┬" in lines[label - 1], lines[label - 1])
    assertFalse("▼" in lines.drop(label))
  }

  @Test
  fun plainFallbackUsesAsciiAndWords() {
    val text = map("before-J2", unicode = false)
    assertTrue(text.all { it.code < 128 || it == '·' }, text)
    assertContains(text, "done")
    assertContains(text, "in progress")
    assertContains(text, "to do")
    assertContains(text, "> CHW phone")
    assertContains(text, "+--")
    assertContains(text.lines().single { "J4.1" in it }, "|<-+")
    assertFalse("\u001B" in text)
  }
}
