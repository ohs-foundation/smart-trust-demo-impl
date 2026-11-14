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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import dev.ohs.trustdemo.onesa.ui.theme.Tone

/**
 * The outcome of a check, boxed in its [tone]: [Tone.Success] for a valid signature or a confirmed
 * match, [Tone.Danger] for a failed one, [Tone.Warning] for one that needs attention. A [verdict]
 * such as PASS or FAIL sits at the end of the [title]; [fields] and a [detail] say what was
 * checked.
 */
@Composable
fun VerificationResultCard(
  title: String,
  tone: Tone,
  modifier: Modifier = Modifier,
  verdict: String? = null,
  fields: List<Field> = emptyList(),
  detail: String? = null,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = tone.soft,
    border = BorderStroke(2.dp, tone.edge),
  ) {
    Column(
      Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val (icon, tint) =
          when (tone) {
            Tone.Danger -> OnesaIcons.ShieldX to tone.ink
            Tone.Warning -> OnesaIcons.Alert to tone.ink
            else -> OnesaIcons.ShieldCheck to OnesaColors.VerifiedIcon
          }
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Text(
          title,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
          color = tone.ink,
        )
        verdict?.let {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color.White,
            contentColor = tone.ink,
            border = BorderStroke(1.dp, tone.edge),
          ) {
            Text(
              it,
              Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
              style = OnesaTheme.mono.verdict,
            )
          }
        }
      }
      if (fields.isNotEmpty()) FieldGrid(fields, columns = 2)
      detail?.let {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Color.White.copy(alpha = 0.7f),
          border = BorderStroke(1.dp, tone.edge),
        ) {
          Text(
            it,
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = OnesaColors.Slate700,
          )
        }
      }
    }
  }
}
