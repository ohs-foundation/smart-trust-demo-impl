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
package dev.ohs.trustdemo.onesa.feature.wallet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ohs.trustdemo.onesa.data.sharing.IssuedLink
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalTestApi::class)
class IssuedPasscodeTest {
  private val today = LocalDate.parse("2026-10-26")

  private fun link(passcode: String?) =
    IssuedLink("c-somsak", "somsak", "Somsak Sengmany", "HC1:TEST", 1_797_292_800, passcode)

  @Test
  fun thePasscodeIsMaskedUntilTheHolderShowsIt() = runComposeUiTest {
    var shown by mutableStateOf(false)
    setContent {
      OnesaTheme(OnesaColors.BrandFamily) {
        IssuedLinksContent(
          listOf(link("4829")),
          emptyList(),
          0,
          false,
          today,
          {},
          {},
          {},
          holder = "FTH",
          passcodeShown = shown,
          onPasscodeShown = { shown = it },
        )
      }
    }
    onNodeWithText("Passcode Protection (Required at PoC) — Father's Verbal Passcode")
      .assertExists()
    assertEquals(4, onAllNodesWithText("•").fetchSemanticsNodes().size)
    assertTrue(onAllNodesWithText("4").fetchSemanticsNodes().isEmpty())
    onNodeWithText("ACTIVE • Reveal to read it out to the clinician").assertExists()
    val toggle = onNodeWithText("Show passcode")
    toggle.assertIsOff()
    val height = toggle.fetchSemanticsNode().boundsInRoot.height / density.density
    assertTrue(height >= 48f, "the toggle is $height dp high")

    toggle.performClick()

    onNodeWithText("Hide passcode").assertIsOn()
    "4829".forEach { onNodeWithText(it.toString()).assertExists() }
    assertTrue(onAllNodesWithText("•").fetchSemanticsNodes().isEmpty())
    onNodeWithText("ACTIVE • Read it out to the clinician; never printed in the QR").assertExists()
  }

  @Test
  fun aLinkFromBeforePasscodesWereKeptSaysSo() = runComposeUiTest {
    setContent {
      OnesaTheme(OnesaColors.BrandFamily) {
        IssuedLinksContent(listOf(link(null)), emptyList(), 0, false, today, {}, {}, {})
      }
    }
    onNodeWithText("Not kept for links made before this update").assertExists()
    assertTrue(onAllNodesWithText("Show passcode").fetchSemanticsNodes().isEmpty())
    assertTrue(onAllNodesWithText("•").fetchSemanticsNodes().isEmpty())
  }
}
