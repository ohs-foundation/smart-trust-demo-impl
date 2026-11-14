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

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code as Unicode half-blocks, two modules to a character. Light modules are painted, so it
 * scans from a dark terminal.
 */
object TerminalQr {
  private const val QUIET_ZONE = 4

  fun render(text: String): String {
    val matrix =
      QRCodeWriter()
        .encode(
          text,
          BarcodeFormat.QR_CODE,
          0,
          0,
          mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to QUIET_ZONE,
          ),
        )
    fun light(x: Int, y: Int) = y >= matrix.height || !matrix.get(x, y)
    return (0 until matrix.height step 2).joinToString("\n") { y ->
      (0 until matrix.width).joinToString("") { x ->
        when {
          light(x, y) && light(x, y + 1) -> "█"
          light(x, y) -> "▀"
          light(x, y + 1) -> "▄"
          else -> " "
        }
      }
    }
  }
}
