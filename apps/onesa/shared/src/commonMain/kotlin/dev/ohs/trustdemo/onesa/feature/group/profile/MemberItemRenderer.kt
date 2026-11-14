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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ohs.player.client.renderer.ComponentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.trustdemo.onesa.data.repository.personName
import dev.ohs.trustdemo.onesa.data.repository.shortAge
import dev.ohs.trustdemo.onesa.generated.config.MemberItemConfig
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.member_head
import dev.ohs.trustdemo.onesa.generated.resources.name_unknown
import dev.ohs.trustdemo.onesa.generated.resources.relationship_child
import dev.ohs.trustdemo.onesa.generated.resources.relationship_father
import dev.ohs.trustdemo.onesa.generated.resources.relationship_guardian
import dev.ohs.trustdemo.onesa.generated.resources.relationship_mother
import dev.ohs.trustdemo.onesa.generated.resources.relationship_non_relative
import dev.ohs.trustdemo.onesa.generated.resources.relationship_other_relative
import dev.ohs.trustdemo.onesa.generated.resources.relationship_parent
import dev.ohs.trustdemo.onesa.generated.resources.relationship_spouse
import dev.ohs.trustdemo.onesa.generated.state.GroupMemberState
import dev.ohs.trustdemo.onesa.ui.RecordRow
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The member [LocalHouseholdHead] names heads the household; null when no record says who does. */
val LocalHouseholdHead = compositionLocalOf<String?> { null }

class MemberItemRenderer : ComponentRenderer<GroupMemberState, MemberItemConfig> {
  @Composable
  override fun Render(item: GroupMemberState, config: MemberItemConfig, options: RenderOptions) {
    MemberItemRow(
      item = item,
      config = config,
      onClick = options.onClick,
      modifier = options.modifier,
    )
  }
}

/**
 * A household member: name, then relation and age (or sex), and whether they head the household.
 */
@Composable
fun MemberItemRow(
  item: GroupMemberState,
  config: MemberItemConfig = MemberItemConfig(),
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
) {
  val name =
    personName(item.memberGivenName, item.memberFamilyName).ifBlank {
      stringResource(Res.string.name_unknown)
    }
  val relation =
    item.relationshipCode?.takeIf { config.showRelationship != false }?.toRelationshipLabel()
      ?: item.memberGender
        ?.takeIf { config.showGender != false }
        ?.replaceFirstChar { it.uppercaseChar() }
  val age = shortAge(item.memberBirthDate?.toString(), today)?.takeIf { config.showAge != false }
  val head = item.memberId != null && item.memberId == LocalHouseholdHead.current
  RecordRow(
    label = name,
    modifier = modifier,
    detail =
      listOfNotNull(
          listOfNotNull(relation, age?.let { "($it)" }).joinToString(" ").ifEmpty { null },
          if (head) stringResource(Res.string.member_head) else null,
        )
        .joinToString(" • ")
        .ifEmpty { null },
    icon = icon,
    onClick = onClick,
  )
}

private fun String.toRelationshipLabelResource(): StringResource? =
  when (uppercase()) {
    "FTH" -> Res.string.relationship_father
    "MTH" -> Res.string.relationship_mother
    "SPS",
    "SPOUSE" -> Res.string.relationship_spouse
    "CHLD",
    "CHILD" -> Res.string.relationship_child
    "PRN",
    "PARENT" -> Res.string.relationship_parent
    "GUAR",
    "GUARDIAN" -> Res.string.relationship_guardian
    "EXT",
    "RELATIVE" -> Res.string.relationship_other_relative
    "FRND",
    "NON-RELATIVE" -> Res.string.relationship_non_relative
    else -> null
  }

@Composable
private fun String.toRelationshipLabel(): String =
  toRelationshipLabelResource()?.let { stringResource(it) }
    ?: lowercase()
      .split('-', '_')
      .filter { it.isNotBlank() }
      .joinToString(" ") { token -> token.replaceFirstChar { it.uppercaseChar() } }
      .ifBlank { this }
