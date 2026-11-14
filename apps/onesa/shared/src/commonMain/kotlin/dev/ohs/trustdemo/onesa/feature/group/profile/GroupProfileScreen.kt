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
package dev.ohs.trustdemo.onesa.feature.group.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.feature.home.RoleTopBar
import dev.ohs.trustdemo.onesa.feature.role.ScreenAction
import dev.ohs.trustdemo.onesa.feature.role.actions
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.group_profile_born_abroad
import dev.ohs.trustdemo.onesa.generated.resources.group_profile_default_name
import dev.ohs.trustdemo.onesa.generated.resources.group_profile_footnote
import dev.ohs.trustdemo.onesa.generated.resources.group_profile_members
import dev.ohs.trustdemo.onesa.generated.resources.group_profile_register_newborn
import dev.ohs.trustdemo.onesa.generated.resources.group_profile_scan_card
import dev.ohs.trustdemo.onesa.generated.state.GroupMemberState
import dev.ohs.trustdemo.onesa.generated.viewtype.ViewTypeCS
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A household and its members. A CHW starts a newborn's registration here: from the family's
 * foreign cards, or from the form alone. [showHeader] is false where Home's header already shows.
 */
@Composable
fun GroupProfileScreen(
  groupId: String,
  role: AppRole,
  onBack: () -> Unit,
  onMemberClick: (String) -> Unit,
  onAddMembers: () -> Unit,
  onRegisterNewborn: () -> Unit = {},
  showHeader: Boolean = true,
) {
  val viewModel = koinViewModel<GroupProfileViewModel>(key = groupId) { parametersOf(groupId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val loaded = state
  if (loaded == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }
  GroupProfileContent(
    state = loaded,
    topBar = { if (showHeader) RoleTopBar(role, onBack) },
    actions =
      if (ScreenAction.AddNewborn in role.actions()) {
        listOf(
          Action(stringResource(Res.string.group_profile_scan_card), onClick = onAddMembers),
          Action(
            stringResource(Res.string.group_profile_register_newborn),
            onClick = onRegisterNewborn,
            primary = false,
          ),
        )
      } else emptyList(),
    onMemberClick = onMemberClick,
  )
}

@Composable
fun GroupProfileContent(
  state: GroupProfileUiState,
  topBar: @Composable () -> Unit,
  actions: List<Action>,
  onMemberClick: (String) -> Unit,
) {
  val registry = LocalViewRegistry.current
  val member =
    remember(registry) { registry.componentRenderer<GroupMemberState>(ViewTypeCS.MemberItem) }
  val header = state.groupHeader
  OnesaScaffold(
    topBar = topBar,
    actions =
      actions
        .takeIf { it.isNotEmpty() }
        ?.let {
          { PrimaryActionBar(it, caption = stringResource(Res.string.group_profile_born_abroad)) }
        },
  ) {
    SectionCard(
      title = header?.groupName ?: stringResource(Res.string.group_profile_default_name),
      summary =
        Status(stringResource(Res.string.group_profile_members, state.members.size), Tone.Info),
      footnote = header?.householdId?.let { stringResource(Res.string.group_profile_footnote, it) },
    ) {
      CompositionLocalProvider(LocalHouseholdHead provides state.headMemberId) {
        state.members.forEach { item ->
          member.Render(item, RenderOptions(onClick = { item.memberId?.let(onMemberClick) }))
        }
      }
    }
  }
}
