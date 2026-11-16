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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * tile ([onIconClick]) does what [iconLabel] names, such as going back with [OnesaIcons.Back]; a
 * tappable status ([onStatusClick]) moves on, such as to the next traveller. [trailing], such as
 * the [AccountButton], ends the row. On a phone the [status] joins the chips under the subtitle.
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
  onStatusClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
) {
  BoxWithConstraints(
    modifier
      .fillMaxWidth()
      .background(Color.White)
      .windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
      )
  ) {
    val statusBelow = maxWidth < 600.dp
    val statusChip: @Composable (Status, Modifier) -> Unit = { chip, sized ->
      StatusChip(
        chip,
        sized.then(
          if (onStatusClick != null)
            Modifier.minimumInteractiveComponentSize()
              .clip(CircleShape)
              .clickable(role = Role.Button, onClick = onStatusClick)
          else Modifier
        ),
      )
    }
    Column(Modifier.fillMaxWidth()) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .padding(vertical = 6.dp)
            .padding(start = 8.dp, end = if (trailing != null) 8.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconTile(icon, onIconClick, iconLabel)
        Column(Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.titleMedium, color = OnesaColors.Ink)
          subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = OnesaColors.Muted)
          }
          val below = status?.takeIf { statusBelow }
          if (chips.isNotEmpty() || below != null) {
            FlowRow(
              modifier = Modifier.padding(top = 3.dp),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              chips.forEach { StatusChip(it) }
              below?.let { statusChip(it, Modifier) }
            }
          }
        }
        if (!statusBelow) status?.let { statusChip(it, Modifier.widthIn(max = 160.dp)) }
        trailing?.invoke()
      }
      HorizontalDivider(color = OnesaColors.Line)
    }
  }
}

/**
 * The signed-in account's button for the end of a root screen's header, named [label]; it opens
 * [menu], such as who is signed in and Sign Out.
 */
@Composable
fun AccountButton(label: String, menu: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    Box(
      Modifier.minimumInteractiveComponentSize()
        .size(36.dp)
        .clip(CircleShape)
        .background(Color.White)
        .border(1.dp, OnesaColors.Slate300, CircleShape)
        .clickable(role = Role.Button) { open = true }
        .semantics { contentDescription = label },
      contentAlignment = Alignment.Center,
    ) {
      Icon(OnesaIcons.User, null, tint = OnesaColors.Slate700, modifier = Modifier.size(18.dp))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
  }
}

@Composable
private fun IconTile(icon: ImageVector, onClick: (() -> Unit)?, label: String?) {
  val brand = MaterialTheme.colorScheme.primary
  val shape = RoundedCornerShape(11.dp)
  Box(
    modifier =
      Modifier.minimumInteractiveComponentSize()
        .size(36.dp)
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
    Icon(icon, contentDescription = null, tint = brand, modifier = Modifier.size(18.dp))
  }
}
