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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors

/**
 * The screen header every Onesa mockup opens with: a brand-tinted icon tile, the place or task as
 * [title], who and where as [subtitle], [chips] under them and one [status] at the end. A tappable
 * tile ([onIconClick]) opens what [iconLabel] names, such as the account menu.
 */
@Composable
fun OnesaTopBar(
  title: String,
  icon: ImageVector,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  chips: List<Status> = emptyList(),
  status: Status? = null,
  onIconClick: (() -> Unit)? = null,
  iconLabel: String? = null,
) {
  Column(
    modifier
      .fillMaxWidth()
      .background(Color.White)
      .windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
      )
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconTile(icon, onIconClick, iconLabel)
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = OnesaColors.Ink)
        subtitle?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = OnesaColors.Muted)
        }
        if (chips.isNotEmpty()) {
          FlowRow(
            modifier = Modifier.padding(top = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            chips.forEach { StatusChip(it) }
          }
        }
      }
      status?.let { StatusChip(it, Modifier.widthIn(max = 160.dp)) }
    }
    HorizontalDivider(color = OnesaColors.Line)
  }
}

@Composable
private fun IconTile(icon: ImageVector, onClick: (() -> Unit)?, label: String?) {
  val brand = MaterialTheme.colorScheme.primary
  val shape = RoundedCornerShape(9.dp)
  Box(
    modifier =
      Modifier.size(34.dp)
        .clip(shape)
        .background(brand.copy(alpha = 0.12f).compositeOver(Color.White))
        .border(BorderStroke(1.dp, brand.copy(alpha = 0.3f).compositeOver(Color.White)), shape)
        .then(
          if (onClick != null) {
            Modifier.clickable(role = Role.Button, onClick = onClick).semantics {
              label?.let { contentDescription = it }
            }
          } else Modifier
        ),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = brand, modifier = Modifier.size(16.dp))
  }
}
