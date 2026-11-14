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

import com.github.ajalt.clikt.testing.test
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.ohs.trustdemo.trustkit.TestKeys
import dev.ohs.trustdemo.trustkit.TrustListWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import who.smart.trust.core.DEFAULT_LIMITS
import who.smart.trust.core.Hcert
import who.smart.trust.trust.TrustStore
import who.smart.trust.verifier.VerificationResult
import who.smart.trust.verifier.VerifierOptions

private const val NOW = 1_794_000_000L
private const val SCALE = 4

private fun qrText(card: String) = QrCommand().test("$card --text").stdout.trim()

private fun combinedStore(): TrustStore =
  Hcert.trust.newTrustStore().apply {
    loadTrustDomains(
      checkNotNull(TrustStore::class.java.getResource("/CodeSystem-TrustDemoTrustDomain.json"))
        .readText()
    )
    addVerifiedDidDocument(TrustListWriter.localTestList(), TestKeys.gdhcnSignerDidDocument())
    addVerifiedDidDocument(whoResource("root-did.json"), whoResource("WHO-signing-did.json"))
  }

private fun whoResource(name: String) =
  checkNotNull(TrustStore::class.java.getResource("/who/$name")).readText()

private fun failureReason(text: String, store: TrustStore): String {
  val result =
    Hcert.verifier.verify(
      text,
      store,
      Hcert.subclaims.newRegistry(),
      NOW,
      VerifierOptions(DEFAULT_LIMITS, clockSkewSeconds = 300),
    )
  return assertIs<VerificationResult.Failed>(result).value.reason.name
}

private fun decodeHalfBlocks(rendering: String): String {
  val rows =
    rendering
      .lines()
      .filter { it.isNotEmpty() }
      .flatMap { line ->
        listOf(line.map { it == '█' || it == '▀' }, line.map { it == '█' || it == '▄' })
      }
  val width = rows.first().size
  val pixels =
    IntArray(width * SCALE * rows.size * SCALE) { at ->
      val light = rows[at / (width * SCALE) / SCALE][at % (width * SCALE) / SCALE]
      if (light) -1 else 0xFF000000.toInt()
    }
  val source = RGBLuminanceSource(width * SCALE, rows.size * SCALE, pixels)
  return QRCodeReader()
    .decode(BinaryBitmap(HybridBinarizer(source)))
    .also { assertEquals(BarcodeFormat.QR_CODE, it.barcodeFormat) }
    .text
}

class QrTest {
  @Test
  fun unknownKeyLinkFailsTheTrustLookup() =
    assertEquals("KID_NOT_FOUND", failureReason(qrText("unknown-key-link"), combinedStore()))

  @Test
  fun renderedQrDecodesBackToTheText() {
    val text = qrText("who-hepb")
    assertEquals(text, decodeHalfBlocks(TerminalQr.render(text)))
  }
}
