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
package dev.ohs.trustdemo.onesa.feature.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ohs.player.client.renderer.ConfiguredRenderer
import dev.ohs.player.client.renderer.LayoutRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.trustdemo.onesa.generated.config.SectionCardConfig
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.section_collapse
import dev.ohs.trustdemo.onesa.generated.resources.section_expand
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource

/**
 * Layout renderer that lists items in a kit [SectionCard] under a [title], with the item count as
 * its chip. Registered under [dev.ohs.trustdemo.onesa.generated.viewtype.ViewTypeCS.SectionCard].
 * [SectionCardConfig] can hide the count or let a tap on the card fold the items away.
 */
class SectionCardLayoutRenderer<T>(
  private val title: String,
  private val icon: ImageVector,
  private val config: SectionCardConfig = SectionCardConfig(),
) : LayoutRenderer<T> {

  @Composable
  override fun Render(
    items: List<T>,
    component: ConfiguredRenderer<T>,
    key: ((T) -> Any)?,
    onItemClick: (T) -> Unit,
    modifier: Modifier,
  ) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val collapsible = config.collapsible == true
    val toggleLabel =
      stringResource(if (expanded) Res.string.section_collapse else Res.string.section_expand)
    SectionCard(
      modifier =
        if (collapsible) {
          modifier.clickable { expanded = !expanded }.semantics { stateDescription = toggleLabel }
        } else modifier,
      title = title,
      icon = icon,
      summary =
        if (config.showItemCount != false) Status(items.size.toString(), Tone.Info) else null,
    ) {
      AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          items.forEach { item ->
            component.Render(item, RenderOptions(onClick = { onItemClick(item) }))
          }
        }
      }
    }
  }
}
