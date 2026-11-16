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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * The dark viewfinder block: a reticle (holding the decoded [frame] as a QR code once a card is
 * read), a bar with the [label] and a [badge], and a [caption].
 *
 * The live [camera] is a mode, not a layer: [openCameraLabel] opens it over the whole screen, and
 * its own close button, Back, or a read card ends it through the `close` it is given. Nothing on
 * the screen, such as a field to paste the card text, sits under a running camera.
 */
@Composable
fun CameraPanel(
  label: String,
  modifier: Modifier = Modifier,
  caption: String? = null,
  badge: Status? = null,
  frame: String? = null,
  openCameraLabel: String? = null,
  camera: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = OnesaColors.Slate950,
    contentColor = OnesaColors.Slate300,
    border = BorderStroke(2.dp, OnesaColors.Slate800),
  ) {
    Column(
      Modifier.padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Reticle(frame)
      Row(
        Modifier.fillMaxWidth()
          .background(OnesaColors.Ink.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
          .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(OnesaIcons.Scan, null, tint = OnesaColors.Sky400, modifier = Modifier.size(14.dp))
        Text(
          label,
          Modifier.weight(1f),
          style = OnesaTheme.mono.caption,
          color = OnesaColors.Slate300,
        )
        badge?.let { StatusChip(it) }
      }
      caption?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = OnesaColors.Slate400,
          textAlign = TextAlign.Center,
        )
      }
      if (camera != null && openCameraLabel != null) {
        OnesaButton(
          Action(openCameraLabel, onClick = { open = true }, icon = OnesaIcons.Scan),
          Modifier.fillMaxWidth(),
        )
      }
    }
  }
  if (open && camera != null) {
    Dialog(
      onDismissRequest = { open = false },
      properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
      Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        camera { open = false }
      }
    }
  }
}

@Composable
private fun Reticle(frame: String?) {
  val shape = RoundedCornerShape(12.dp)
  Box(
    Modifier.widthIn(max = 150.dp)
      .fillMaxWidth(0.7f)
      .aspectRatio(1f)
      .drawBehind { corners() }
      .border(2.dp, OnesaColors.Reticle, shape)
      .padding(10.dp)
  ) {
    frame?.let {
      Image(
        rememberQrCodePainter(it),
        contentDescription = null,
        modifier =
          Modifier.fillMaxSize()
            .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
            .padding(4.dp),
      )
    }
  }
}

private fun DrawScope.corners() {
  val arm = 14.dp.toPx()
  val stroke = 3.dp.toPx()
  val out = 5.dp.toPx() - stroke / 2
  val l = -out
  val t = -out
  val r = size.width + out
  val b = size.height + out
  listOf(
      Triple(Offset(l, t), Offset(arm, 0f), Offset(0f, arm)),
      Triple(Offset(r, t), Offset(-arm, 0f), Offset(0f, arm)),
      Triple(Offset(l, b), Offset(arm, 0f), Offset(0f, -arm)),
      Triple(Offset(r, b), Offset(-arm, 0f), Offset(0f, -arm)),
    )
    .forEach { (corner, h, v) ->
      drawLine(OnesaColors.ReticleCorner, corner, corner + h, stroke, StrokeCap.Square)
      drawLine(OnesaColors.ReticleCorner, corner, corner + v, stroke, StrokeCap.Square)
    }
}
