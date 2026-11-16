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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import dev.ohs.trustdemo.onesa.ui.theme.Tone

/** A short status in a [tone], such as "Queued", "primarySource: false" or "2 Members". */
@Immutable
data class Status(val text: String, val tone: Tone = Tone.Neutral, val icon: ImageVector? = null)

/**
 * A pill in monospace that names a state. [onDark] recolours it for the dark patient banner, as the
 * build map mixes each tone into slate there.
 */
@Composable
fun StatusChip(
  status: Status,
  modifier: Modifier = Modifier,
  onDark: Boolean = false,
  textAlign: TextAlign? = null,
) {
  val tone = status.tone
  val fill = if (onDark) tone.ink.copy(alpha = 0.35f).compositeOver(OnesaColors.Ink) else tone.soft
  val edge = if (onDark) tone.ink.copy(alpha = 0.6f).compositeOver(OnesaColors.Ink) else tone.edge
  val ink = if (onDark) lerp(tone.edge, Color.White, 0.2f) else tone.ink
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = fill,
    contentColor = ink,
    border = BorderStroke(1.dp, edge),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      status.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(12.dp)) }
      Text(status.text, style = OnesaTheme.mono.chip, textAlign = textAlign)
    }
  }
}
