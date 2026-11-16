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
package dev.ohs.trustdemo.onesa.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.home_account
import dev.ohs.trustdemo.onesa.generated.resources.home_back
import dev.ohs.trustdemo.onesa.ui.AccountButton
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.PillTabs
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.Status
import org.jetbrains.compose.resources.stringResource

/** What the shell's header says for the signed-in role. */
@Immutable
data class ShellHeader(
  val title: String,
  val icon: ImageVector,
  val subtitle: String? = null,
  val chips: List<Status> = emptyList(),
  val status: Status? = null,
)

/**
 * The signed-in app's frame: the role's header, whose account button opens the [menu] and whose
 * icon tile turns into a back arrow when [onBack] is set, pill [tabs] when the role has more than
 * one place to go, the tab's [content] with any [side] blocks beside it on a tablet, and its
 * [actions] with their [caption].
 */
@Composable
fun HomeShell(
  header: ShellHeader,
  tabs: List<String>,
  selected: Int,
  onSelect: (Int) -> Unit,
  menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
  modifier: Modifier = Modifier,
  actions: List<Action>? = null,
  snackbarHost: @Composable () -> Unit = {},
  side: (@Composable ColumnScope.() -> Unit)? = null,
  caption: String? = null,
  onBack: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  OnesaScaffold(
    modifier = modifier,
    topBar = {
      OnesaTopBar(
        title = header.title,
        icon = if (onBack != null) OnesaIcons.Back else header.icon,
        subtitle = header.subtitle,
        chips = header.chips,
        status = header.status,
        onIconClick = onBack,
        iconLabel = stringResource(Res.string.home_back),
        trailing = { AccountButton(stringResource(Res.string.home_account), menu) },
      )
    },
    actions = actions?.let { { PrimaryActionBar(it, caption = caption) } },
    side = side,
    scroll = false,
    snackbarHost = snackbarHost,
  ) {
    if (tabs.size > 1) PillTabs(tabs, selected, onSelect)
    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
  }
}
