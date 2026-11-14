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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.trust_list_empty
import dev.ohs.trustdemo.onesa.generated.viewtype.ViewTypeCS
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The trust list tab (1-15): each list the app holds and the keys of the one it fetches. */
@Composable
fun TrustListScreen() {
  val viewModel: TrustListViewModel = koinViewModel()
  val lists by viewModel.lists.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { viewModel.load() }
  val items = lists
  if (items == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }
  TrustListContent(items)
}

/** The status card of each list bundled with the app. */
@Composable
fun TrustListContent(lists: List<TrustListState>) {
  val registry = LocalViewRegistry.current
  val card =
    remember(registry) { registry.componentRenderer<TrustListState>(ViewTypeCS.TrustListCard) }
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (lists.isEmpty()) Text(stringResource(Res.string.trust_list_empty))
    lists.filter { it.bundled }.forEach { card.Render(it, RenderOptions()) }
  }
}

/** The trust list tab's side column, beside [TrustListScreen]. */
@Composable
fun TrustListSide() {
  val viewModel: TrustListViewModel = koinViewModel()
  val lists by viewModel.lists.collectAsStateWithLifecycle()
  TrustKeys(lists.orEmpty())
}

/** Beside the status cards: the keys of each list the app fetches. */
@Composable
fun TrustKeys(lists: List<TrustListState>) {
  lists.filterNot { it.bundled }.forEach { TrustKeysCard(it) }
}
