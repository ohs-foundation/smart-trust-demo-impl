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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme

/**
 * One record in a [SectionCard]: a [label], its [status] chip, a [code] line in monospace (a
 * reference, product code or lot) and a [detail] line, outlined in the brand when [selected].
 * Household members, doses, referrals and queued uploads all take this shape.
 */
@Composable
fun RecordRow(
  label: String,
  modifier: Modifier = Modifier,
  code: String? = null,
  detail: String? = null,
  status: Status? = null,
  icon: ImageVector? = null,
  selected: Boolean = false,
  onClick: (() -> Unit)? = null,
) {
  val shape = RoundedCornerShape(11.dp)
  val fill = if (selected) MaterialTheme.colorScheme.primaryContainer else OnesaColors.Bg
  val edge =
    BorderStroke(
      if (selected) 1.5.dp else 1.dp,
      if (selected) MaterialTheme.colorScheme.primary else OnesaColors.Line,
    )
  val content: @Composable () -> Unit = {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      icon?.let { RowIcon(it) }
      Column(Modifier.weight(1f)) {
        BoxWithConstraints {
          val chipWidth = minOf(160.dp, maxWidth * 0.5f)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              label,
              modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = OnesaColors.Ink,
            )
            status?.let {
              StatusChip(it, Modifier.widthIn(max = chipWidth), textAlign = TextAlign.End)
            }
          }
        }
        code?.let { Text(it, style = OnesaTheme.mono.code, color = OnesaColors.Muted) }
        detail?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = OnesaColors.Slate500)
        }
      }
    }
  }
  if (onClick != null) {
    Surface(
      onClick = onClick,
      modifier = modifier.fillMaxWidth(),
      shape = shape,
      color = fill,
      border = edge,
    ) {
      content()
    }
  } else {
    Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = fill, border = edge) {
      content()
    }
  }
}

@Composable
private fun RowIcon(icon: ImageVector) {
  val shape = RoundedCornerShape(10.dp)
  Box(
    Modifier.size(30.dp).background(Color.White, shape).border(1.dp, OnesaColors.Line, shape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
  }
}
