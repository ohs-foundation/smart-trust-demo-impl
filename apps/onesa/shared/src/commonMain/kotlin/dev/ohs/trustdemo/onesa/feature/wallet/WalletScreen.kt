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
package dev.ohs.trustdemo.onesa.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.wallet_empty
import dev.ohs.trustdemo.onesa.generated.resources.wallet_household
import dev.ohs.trustdemo.onesa.generated.resources.wallet_not_synced
import dev.ohs.trustdemo.onesa.generated.resources.wallet_share
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** S6: the household the signed-in family member manages, and the way to share its records. */
@Composable
fun WalletScreen(onShare: () -> Unit) {
  val viewModel: WalletViewModel = koinViewModel()
  val members by viewModel.members.collectAsStateWithLifecycle()

  val household = members
  if (household == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }
  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(stringResource(Res.string.wallet_household), style = MaterialTheme.typography.titleMedium)
    if (household.isEmpty()) Text(stringResource(Res.string.wallet_empty))
    household.forEach {
      Text(
        it.name ?: stringResource(Res.string.wallet_not_synced),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    Button(
      onClick = onShare,
      enabled = household.isNotEmpty(),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(Res.string.wallet_share))
    }
  }
}
