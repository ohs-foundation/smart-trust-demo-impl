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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors

/**
 * A white card with a [title] (and its [icon]), a [summary] chip at the end of the title line, the
 * card's [content] and a [footnote].
 */
@Composable
fun SectionCard(
  modifier: Modifier = Modifier,
  title: String? = null,
  icon: ImageVector? = null,
  summary: Status? = null,
  footnote: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = Color.White,
    border = BorderStroke(1.dp, OnesaColors.Line),
    shadowElevation = 0.5.dp,
  ) {
    Column(
      Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (title != null || summary != null) {
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalArrangement = Arrangement.spacedBy(6.dp),
          itemVerticalAlignment = Alignment.CenterVertically,
        ) {
          title?.let { CardTitle(it, icon) }
          summary?.let { StatusChip(it) }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
      footnote?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = OnesaColors.Slate500)
      }
    }
  }
}

@Composable
internal fun CardTitle(title: String, icon: ImageVector?, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    icon?.let {
      Icon(it, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
    }
    Text(title, style = MaterialTheme.typography.titleSmall, color = OnesaColors.Ink)
  }
}
