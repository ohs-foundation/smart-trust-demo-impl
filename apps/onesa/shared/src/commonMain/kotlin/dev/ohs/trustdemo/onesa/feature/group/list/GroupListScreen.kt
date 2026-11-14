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
package dev.ohs.trustdemo.onesa.feature.group.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.trustdemo.onesa.feature.home.rememberPlace
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.group_list_empty
import dev.ohs.trustdemo.onesa.generated.resources.group_list_footnote
import dev.ohs.trustdemo.onesa.generated.resources.group_list_place
import dev.ohs.trustdemo.onesa.generated.resources.group_list_title
import dev.ohs.trustdemo.onesa.generated.state.GroupListState
import dev.ohs.trustdemo.onesa.generated.viewtype.ViewTypeCS
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The household register: the households of the user's catchment or facility. */
@Composable
fun GroupListScreen(onGroupClick: (String) -> Unit) {
  val viewModel: GroupListViewModel = koinViewModel()
  val groups by viewModel.groups.collectAsStateWithLifecycle()
  val profiles by viewModel.profiles.collectAsStateWithLifecycle()
  val loaded = groups
  if (loaded == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }
  GroupListContent(loaded, profiles, rememberPlace(), onGroupClick)
}

/** The register's card: one row per household, rendered by the registered GroupCard view. */
@Composable
fun GroupListContent(
  groups: List<GroupListState>,
  profiles: List<String>,
  place: String?,
  onGroupClick: (String) -> Unit,
) {
  val registry = LocalViewRegistry.current
  val card = remember(registry) { registry.componentRenderer<GroupListState>(ViewTypeCS.GroupCard) }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SectionCard(
      title =
        place?.let { stringResource(Res.string.group_list_place, it) }
          ?: stringResource(Res.string.group_list_title),
      summary = Status(groups.size.toString(), Tone.Info),
      footnote =
        profiles
          .takeIf { it.isNotEmpty() }
          ?.let { stringResource(Res.string.group_list_footnote, it.joinToString(", ")) },
    ) {
      if (groups.isEmpty()) {
        Text(
          stringResource(Res.string.group_list_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      groups.forEach { group ->
        card.Render(group, RenderOptions(onClick = { onGroupClick(group.groupId ?: "") }))
      }
    }
  }
}
