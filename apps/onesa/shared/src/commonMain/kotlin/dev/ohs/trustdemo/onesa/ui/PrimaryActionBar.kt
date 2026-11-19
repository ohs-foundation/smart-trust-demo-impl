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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme

/**
 * A button in a [PrimaryActionBar]. [primary] fills it with the brand; [busyLabel] names the
 * spinner shown while [busy], which leaves the button tappable unless [enabled] is false. A toggle
 * says whether it is [pressed]; a disabled button tells a screen reader why in [reason].
 */
@Immutable
data class Action(
  val text: String,
  val onClick: () -> Unit,
  val primary: Boolean = true,
  val icon: ImageVector? = null,
  val enabled: Boolean = true,
  val busy: Boolean = false,
  val busyLabel: String? = null,
  val pressed: Boolean? = null,
  val reason: String? = null,
)

/**
 * The bar pinned under a screen: a [lead] line above its [actions], such as who the main action is
 * for, the actions stacked full width on a phone and lined up at the end on a tablet, and a
 * [caption] in monospace saying what the main action writes.
 */
@Composable
fun PrimaryActionBar(
  actions: List<Action>,
  modifier: Modifier = Modifier,
  caption: String? = null,
  lead: (@Composable () -> Unit)? = null,
) {
  Column(modifier.fillMaxWidth()) {
    HorizontalDivider(color = OnesaColors.Line)
    Column(
      Modifier.fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .windowInsetsPadding(
          WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        ),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      lead?.invoke()
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
          FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
          ) {
            actions.forEach { OnesaButton(it) }
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { OnesaButton(it, Modifier.fillMaxWidth()) }
          }
        }
      }
      caption?.let {
        Text(
          it,
          modifier = Modifier.fillMaxWidth(),
          style = OnesaTheme.mono.caption,
          color = OnesaColors.Slate500,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

/** The build map's button: brand-filled when primary, white with a slate edge otherwise. */
@Composable
fun OnesaButton(action: Action, modifier: Modifier = Modifier) {
  val brand = MaterialTheme.colorScheme.primary
  val fill = if (action.primary) brand else Color.White
  val ink =
    when {
      action.primary -> Color.White
      action.enabled -> OnesaColors.Slate700
      else -> OnesaColors.Slate700.copy(alpha = 0.38f)
    }
  Surface(
    onClick = action.onClick,
    modifier =
      modifier.semantics {
        action.pressed?.let { toggleableState = ToggleableState(it) }
        if (!action.enabled) action.reason?.let { stateDescription = it }
      },
    enabled = action.enabled,
    shape = MaterialTheme.shapes.small,
    color = if (action.enabled) fill else fill.copy(alpha = 0.45f),
    contentColor = ink,
    border = if (action.primary) null else BorderStroke(1.dp, OnesaColors.Slate300),
  ) {
    Row(
      Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (action.busy) {
        CircularProgressIndicator(
          modifier =
            Modifier.size(14.dp).semantics { action.busyLabel?.let { contentDescription = it } },
          strokeWidth = 2.dp,
          color = ink,
        )
      } else {
        action.icon?.let { Icon(it, null, modifier = Modifier.size(16.dp)) }
      }
      Text(action.text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
  }
}
