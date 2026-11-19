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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import dev.ohs.trustdemo.onesa.ui.theme.Tone

/**
 * A boxed message in a [tone]: a warning that something is unverified, an info that explains what
 * happens next, a danger that something was refused.
 */
@Composable
fun AlertCard(title: String?, text: String?, tone: Tone, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = tone.soft,
    contentColor = tone.ink,
    border = BorderStroke(2.dp, tone.edge),
  ) {
    Row(
      Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      val icon =
        if (tone == Tone.Info || tone == Tone.Success) OnesaIcons.Shield else OnesaIcons.Alert
      Icon(icon, null, modifier = Modifier.padding(top = 1.dp).size(20.dp))
      Column {
        title?.let {
          Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        text?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
          )
        }
      }
    }
  }
}

/** A quiet line of guidance under or between blocks, led by an icon; [mono] for a record's code. */
@Composable
fun NoteLine(
  text: String,
  modifier: Modifier = Modifier,
  icon: ImageVector = OnesaIcons.Info,
  mono: Boolean = false,
) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Icon(
      icon,
      null,
      tint = OnesaColors.Slate500,
      modifier = Modifier.padding(top = 1.dp).size(14.dp),
    )
    Text(
      text,
      style = if (mono) OnesaTheme.mono.code else MaterialTheme.typography.bodySmall,
      color = OnesaColors.Slate500,
    )
  }
}
