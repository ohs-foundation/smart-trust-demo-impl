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
package dev.ohs.trustdemo.onesa.ui

import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.ScreenshotHarness.render
import kotlin.test.Test

/** Renders the kit and the shared screens with the mockups' story data, for side-by-side review. */
class OnesaScreensRenderTest {
  @Test
  fun kitSheet() {
    render("kit-sheet", 420.dp to 2960.dp) { KitSheet() }
  }
}
