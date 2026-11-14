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
package dev.ohs.trustdemo.onesa.feature.trustlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.generated.config.TrustListCardConfig
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_domains
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_entries
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_fetched_at
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_not_fetched
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_signer
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_source
import org.jetbrains.compose.resources.stringResource

@Composable
fun TrustListCard(list: TrustListState, config: TrustListCardConfig = TrustListCardConfig()) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = list.name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      list.source?.let { Detail(stringResource(Res.string.trust_list_source, it)) }
      if (config.showSigner != false) {
        list.signer?.let { Detail(stringResource(Res.string.trust_list_signer, it)) }
      }
      Detail(
        list.fetchedAt?.let { stringResource(Res.string.trust_list_fetched_at, it) }
          ?: stringResource(Res.string.trust_list_not_fetched)
      )
      list.entryCount?.let { Detail(stringResource(Res.string.trust_list_entries, it)) }
      if (config.showDomains != false) {
        list.domains?.let { Detail(stringResource(Res.string.trust_list_domains, it)) }
      }
    }
  }
}

@Composable
private fun Detail(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
