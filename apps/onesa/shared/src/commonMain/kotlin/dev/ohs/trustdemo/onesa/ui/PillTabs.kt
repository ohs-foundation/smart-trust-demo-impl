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

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors

/** Pill tabs on a slate track; the [selected] one is filled with the brand. */
@Composable
fun PillTabs(
  items: List<String>,
  selected: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier
      .clip(CircleShape)
      .background(OnesaColors.Line)
      .horizontalScroll(rememberScrollState())
      .padding(4.dp)
      .selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    items.forEachIndexed { index, item ->
      val on = index == selected
      Text(
        item,
        modifier =
          Modifier.clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
            .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) })
            .padding(horizontal = 12.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (on) Color.White else OnesaColors.Slate700,
      )
    }
  }
}
