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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import io.github.alexzhirkevich.qrose.options.QrCodeMatrix
import io.github.alexzhirkevich.qrose.options.QrCodeShape
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * A QR code to show someone: a [title] rule, the [holder] it belongs to, a [label] tag, the code
 * for [payload], a [badge] saying who signed it and a [caption] on what it holds.
 */
@Composable
fun QrPanel(
  payload: String,
  modifier: Modifier = Modifier,
  title: String? = null,
  holder: String? = null,
  holderNote: String? = null,
  label: String? = null,
  badge: Status? = null,
  caption: String? = null,
  contentDescription: String? = label ?: holder,
) {
  SectionCard(modifier) {
    Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      title?.let {
        Text(
          it.uppercase(),
          Modifier.fillMaxWidth(),
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.06.em,
          color = OnesaColors.Slate800,
          textAlign = TextAlign.Center,
        )
        HorizontalDivider(color = OnesaColors.Line)
      }
      holder?.let {
        Column(Modifier.fillMaxWidth()) {
          Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
          holderNote?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, color = OnesaColors.Muted)
          }
        }
      }
      label?.let {
        Text(
          it,
          Modifier.align(Alignment.Start)
            .background(Color(0xFFFFFBEB), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
          style = MaterialTheme.typography.labelMedium,
          color = OnesaColors.QrLabelInk,
        )
      }
      Surface(
        Modifier.widthIn(max = 168.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, OnesaColors.Line),
      ) {
        Image(
          rememberScannableQrPainter(payload),
          contentDescription = contentDescription,
          modifier = Modifier.aspectRatio(1f),
        )
      }
      badge?.let { StatusChip(it, textAlign = TextAlign.Center) }
      caption?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = OnesaColors.Slate500,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

/** A QR code to be scanned: square modules drawn as vectors, inside the standard quiet zone. */
@Composable
fun rememberScannableQrPainter(data: String): Painter =
  rememberQrCodePainter(data) { shapes { pattern = QuietZone } }

private const val QUIET_ZONE_MODULES = 4

/** Light modules around the code, as many a side as ISO/IEC 18004 asks a reader to have. */
private object QuietZone : QrCodeShape {
  override val shapeSizeIncrease: Float = 1f

  override fun QrCodeMatrix.transform(): QrCodeMatrix {
    val code = this
    return QrCodeMatrix(code.size + 2 * QUIET_ZONE_MODULES, QrCodeMatrix.PixelType.Background)
      .apply {
        for (row in 0 until code.size) {
          for (column in 0 until code.size) {
            set(row + QUIET_ZONE_MODULES, column + QUIET_ZONE_MODULES, code[row, column])
          }
        }
      }
  }
}
