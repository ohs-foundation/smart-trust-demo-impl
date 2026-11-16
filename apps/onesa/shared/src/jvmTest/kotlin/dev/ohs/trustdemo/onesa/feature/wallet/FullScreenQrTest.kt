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

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.ohs.trustdemo.onesa.data.sharing.IssuedLink
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FullScreenQrTest {
  private val links =
    listOf("somsak" to "Somsak Sengmany", "bounmy" to "Bounmy Sengmany").map { (id, name) ->
      IssuedLink(
        consentId = "consent-$id",
        personId = id,
        name = name,
        link =
          "https://vhl.onesa.example.org:8443/vhl/manifest/5f0c1d2e3a4b5c6d7e8f9a0b1c2d3e4f" +
            "?token=e4a899bc01d2&exp=1797292800#key=f91b7c3d2e5a8812f91b7c3d2e5a8812-$id",
        exp = 1_797_292_800,
      )
    }

  @Test
  fun theQrIsSquareWholeQuietAndScansAt360DpAndOnA1080By2220Phone() {
    shown(width = 720, height = 1440, density = 2f)
    shown(width = 1080, height = 2220, density = 2.625f)
  }

  @Test
  fun severalQrsAreStepped48DpHighUnderTheCode() =
    runDesktopComposeUiTest(720, 1440) {
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(2f)) {
          OnesaTheme(OnesaColors.BrandFamily) { FullScreenQr(links, 0, {}, {}) }
        }
      }
      onNodeWithText("QR 1 of 2").assertExists()
      listOf("Previous", "Next").forEach { label ->
        val height = onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height
        assertTrue(height >= 48 * 2f, "$label is ${height / 2f} dp high")
      }
    }

  private fun shown(width: Int, height: Int, density: Float) =
    runDesktopComposeUiTest(width, height) {
      val link = links.first()
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(density)) {
          OnesaTheme(OnesaColors.BrandFamily) { FullScreenQr(listOf(link), 0, {}, {}) }
        }
      }
      val bounds = onNodeWithContentDescription(link.name).fetchSemanticsNode().boundsInRoot
      assertEquals(bounds.width, bounds.height, 1f, "the QR is square")
      assertTrue(
        bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height,
        "the QR at $bounds is whole on a $width x $height screen",
      )
      assertTrue(onAllNodesWithText("Previous").fetchSemanticsNodes().isEmpty())
      assertTrue(onAllNodesWithText("Next").fetchSemanticsNodes().isEmpty())

      val screen = onRoot().captureToImage().toAwtImage()
      val pixels = screen.getRGB(0, 0, screen.width, screen.height, null, 0, screen.width)
      val luminance = RGBLuminanceSource(screen.width, screen.height, pixels)
      val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance)))
      assertEquals(link.link, read.text)
      val quiet = quietModules(screen, bounds)
      assertTrue(quiet >= 4f, "the quiet zone is $quiet modules on a $width x $height screen")
    }

  /**
   * The narrowest light margin between the QR's [bounds] and its dark modules, in modules, measured
   * against the top-left finder pattern, which is 7 modules wide; a pixel of rounding is allowed.
   */
  private fun quietModules(image: BufferedImage, bounds: Rect): Float {
    val left = bounds.left.roundToInt()
    val top = bounds.top.roundToInt()
    val right = bounds.right.roundToInt() - 1
    val bottom = bounds.bottom.roundToInt() - 1

    fun dark(x: Int, y: Int): Boolean {
      val rgb = image.getRGB(x, y)
      return ((rgb shr 16 and 0xff) + (rgb shr 8 and 0xff) + (rgb and 0xff)) / 3 < 128
    }

    val darkPixels =
      (top..bottom).flatMap { y -> (left..right).filter { dark(it, y) }.map { it to y } }
    val minX = darkPixels.minOf { it.first }
    val maxX = darkPixels.maxOf { it.first }
    val minY = darkPixels.minOf { it.second }
    val maxY = darkPixels.maxOf { it.second }
    val finderRow = minY + 1
    val finder = (minX..maxX).takeWhile { dark(it, finderRow) }.count()
    val module = finder / 7f
    val margin = minOf(minX - left, minY - top, right - maxX, bottom - maxY) + 1
    return margin / module
  }
}
