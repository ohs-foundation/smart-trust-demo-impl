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

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import java.io.File
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders a composable to `build/screens/<name>.png` at phone or tablet size, so a screen can be
 * set beside its mockup; [settle] extra frames, 50 ms apart, let a screen that loads in the
 * background draw. A render that throws or writes nothing fails the calling test.
 */
object ScreenshotHarness {
  val Phone = 360.dp to 720.dp
  val Tablet = 1024.dp to 640.dp
  private const val SCALE = 2f
  private val out = File("build/screens")

  fun render(
    name: String,
    size: Pair<Dp, Dp> = Phone,
    brand: Color = OnesaColors.BrandOnesa,
    settle: Int = 0,
    content: @Composable () -> Unit,
  ): File {
    val (width, height) = size
    val scene =
      ImageComposeScene(
        width = (width.value * SCALE).toInt(),
        height = (height.value * SCALE).toInt(),
        density = Density(SCALE),
      ) {
        OnesaTheme(brand) { content() }
      }
    val file =
      try {
        scene.render()
        repeat(settle) {
          Thread.sleep(50)
          scene.render((it + 1) * 50_000_000L)
        }
        val png =
          scene.render((settle + 1) * 50_000_000L).encodeToData(EncodedImageFormat.PNG)?.bytes
        out.mkdirs()
        File(out, "$name.png").apply { writeBytes(checkNotNull(png) { "$name did not encode" }) }
      } finally {
        scene.close()
      }
    assertTrue(file.length() > 0, "$name rendered nothing")
    return file
  }
}
