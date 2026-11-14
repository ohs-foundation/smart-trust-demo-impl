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
package dev.ohs.trustdemo.onesa.feature.login

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.login_audience
import dev.ohs.trustdemo.onesa.generated.resources.login_brand
import dev.ohs.trustdemo.onesa.generated.resources.login_card_title
import dev.ohs.trustdemo.onesa.generated.resources.login_error_dismiss
import dev.ohs.trustdemo.onesa.generated.resources.login_error_title
import dev.ohs.trustdemo.onesa.generated.resources.login_ministry
import dev.ohs.trustdemo.onesa.generated.resources.login_offline_note
import dev.ohs.trustdemo.onesa.generated.resources.login_redirect_hint
import dev.ohs.trustdemo.onesa.generated.resources.login_sign_in
import dev.ohs.trustdemo.onesa.generated.resources.login_signing_in
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.NoteLine
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import org.jetbrains.compose.resources.stringResource

/**
 * One sign-in for every Onesan role. The username and password go on the identity provider's page
 * (authorization code with PKCE), so the card says where they go instead of asking for them here.
 */
@Composable
fun LoginScreen(
  signingIn: Boolean,
  error: String?,
  onSignIn: () -> Unit,
  onErrorDismiss: () -> Unit,
) {
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
        actions =
          listOf(
            Action(
              stringResource(Res.string.login_sign_in),
              onClick = onSignIn,
              enabled = !signingIn,
              busy = signingIn,
              busyLabel = stringResource(Res.string.login_signing_in),
            )
          ),
        caption = stringResource(Res.string.login_offline_note),
      )
    },
  ) {
    NoteLine(stringResource(Res.string.login_audience))
    SectionCard(title = stringResource(Res.string.login_card_title)) {
      Text(
        stringResource(Res.string.login_redirect_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = OnesaColors.Muted,
      )
    }
  }

  if (error != null) {
    LoginErrorDialog(message = error, onDismiss = onErrorDismiss)
  }
}

@Composable
private fun LoginErrorDialog(message: String, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(Res.string.login_error_title),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    text = {
      Text(text = message, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(Res.string.login_error_dismiss)) }
    },
    titleContentColor = MaterialTheme.colorScheme.error,
  )
}
