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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import dev.ohs.trustdemo.onesa.data.repository.personName
import dev.ohs.trustdemo.onesa.generated.config.GroupCardConfig
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.group_head
import dev.ohs.trustdemo.onesa.generated.resources.group_member_count_one
import dev.ohs.trustdemo.onesa.generated.resources.group_member_count_other
import dev.ohs.trustdemo.onesa.generated.resources.group_unknown_name
import dev.ohs.trustdemo.onesa.generated.state.GroupListState
import dev.ohs.trustdemo.onesa.ui.RecordRow
import org.jetbrains.compose.resources.stringResource

/**
 * Selected household id for the two-pane list-detail highlight; null when there is no open pane.
 */
val LocalSelectedGroupId = compositionLocalOf<String?> { null }

/** A household in the register: its name, household id, head and size. */
@Composable
fun GroupCard(
  group: GroupListState,
  config: GroupCardConfig = GroupCardConfig(),
  onClick: (() -> Unit)? = null,
) {
  val count = group.memberCount ?: "0"
  val head =
    personName(group.headGivenName, group.headFamilyName)
      .takeIf { it.isNotBlank() }
      ?.let { stringResource(Res.string.group_head, it) }
  val members =
    if (config.showMemberCount != false) {
      stringResource(
        if (count == "1") Res.string.group_member_count_one
        else Res.string.group_member_count_other,
        count,
      )
    } else null
  RecordRow(
    label = group.groupName ?: stringResource(Res.string.group_unknown_name),
    code = group.householdId?.let { "#$it" },
    detail = listOfNotNull(head, members).joinToString(" • ").ifEmpty { null },
    selected = group.groupId != null && group.groupId == LocalSelectedGroupId.current,
    onClick = onClick,
  )
}
