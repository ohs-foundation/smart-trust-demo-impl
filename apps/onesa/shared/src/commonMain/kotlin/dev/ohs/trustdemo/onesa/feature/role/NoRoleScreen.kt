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
package dev.ohs.trustdemo.onesa.feature.role

import androidx.compose.runtime.Composable
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.home_sign_out
import dev.ohs.trustdemo.onesa.generated.resources.login_brand
import dev.ohs.trustdemo.onesa.generated.resources.login_ministry
import dev.ohs.trustdemo.onesa.generated.resources.role_failed_title
import dev.ohs.trustdemo.onesa.generated.resources.role_none_body
import dev.ohs.trustdemo.onesa.generated.resources.role_none_title
import dev.ohs.trustdemo.onesa.generated.resources.role_retry
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource

/** Shown instead of the app when the user's role is unknown ([message] null) or failed to load. */
@Composable
fun NoRoleScreen(message: String?, onRetry: (() -> Unit)?, onSignOut: () -> Unit) {
  OnesaScaffold(
    topBar = {
      OnesaTopBar(
        title = stringResource(Res.string.login_brand),
        icon = OnesaIcons.Mark,
        subtitle = stringResource(Res.string.login_ministry),
      )
    },
    actions = {
      PrimaryActionBar(
        listOfNotNull(
          Action(stringResource(Res.string.home_sign_out), onClick = onSignOut, primary = false),
          onRetry?.let { Action(stringResource(Res.string.role_retry), onClick = it) },
        )
      )
    },
  ) {
    AlertCard(
      title =
        stringResource(
          if (message == null) Res.string.role_none_title else Res.string.role_failed_title
        ),
      text = message ?: stringResource(Res.string.role_none_body),
      tone = if (message == null) Tone.Warning else Tone.Danger,
    )
  }
}
