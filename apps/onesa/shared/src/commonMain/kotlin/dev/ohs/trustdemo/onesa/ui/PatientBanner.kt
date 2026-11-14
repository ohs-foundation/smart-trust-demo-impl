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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors

/** The avatar colour the build map gives a child. */
val ChildAvatar = Color(0xFF65A30D)

/**
 * The dark band naming the patient a screen is about: an avatar, the [name], [chips] such as age
 * and who referred them, and a [subtitle].
 */
@Composable
fun PatientBanner(
  name: String,
  modifier: Modifier = Modifier,
  chips: List<Status> = emptyList(),
  subtitle: String? = null,
  avatar: ImageVector = OnesaIcons.User,
  avatarColor: Color = OnesaColors.Slate500,
) {
  Column(modifier.fillMaxWidth().background(OnesaColors.Ink)) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier.size(32.dp).background(avatarColor, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(avatar, null, tint = Color.White, modifier = Modifier.size(20.dp))
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
          itemVerticalAlignment = Alignment.CenterVertically,
        ) {
          Text(name, style = MaterialTheme.typography.titleMedium, color = Color.White)
          chips.forEach { StatusChip(it, onDark = true) }
        }
        subtitle?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = OnesaColors.Slate400)
        }
      }
    }
    HorizontalDivider(color = OnesaColors.Slate800)
  }
}
