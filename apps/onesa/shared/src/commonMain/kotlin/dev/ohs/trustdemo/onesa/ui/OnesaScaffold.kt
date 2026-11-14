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
package dev.ohs.trustdemo.onesa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors

/**
 * The frame of every Onesa screen: [topBar], an optional [banner] under it, the blocks of [content]
 * on the slate background, and [actions] pinned at the bottom. On a tablet the [side] blocks sit in
 * a column beside the main ones (the wider of the two when [sideWide]); on a phone they follow
 * them.
 */
@Composable
fun OnesaScaffold(
  topBar: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  banner: (@Composable () -> Unit)? = null,
  actions: (@Composable () -> Unit)? = null,
  side: (@Composable ColumnScope.() -> Unit)? = null,
  sideWide: Boolean = false,
  scroll: Boolean = true,
  snackbarHost: @Composable () -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier.fillMaxSize().background(OnesaColors.Bg)) {
    topBar()
    banner?.invoke()
    Box(Modifier.weight(1f).fillMaxWidth()) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        if (side != null && maxWidth >= 840.dp) {
          Row(Modifier.fillMaxSize()) {
            Blocks(Modifier.weight(if (sideWide) 5f else 7f), scroll, content = content)
            Blocks(
              Modifier.weight(if (sideWide) 7f else 5f),
              scroll,
              leading = false,
              content = side,
            )
          }
        } else {
          Blocks(Modifier.fillMaxWidth(), scroll) {
            content()
            side?.invoke(this)
          }
        }
      }
      Box(Modifier.align(Alignment.BottomCenter)) { snackbarHost() }
    }
    actions?.invoke()
  }
}

@Composable
private fun Blocks(
  modifier: Modifier,
  scroll: Boolean,
  leading: Boolean = true,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier
      .fillMaxSize()
      .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
      .padding(start = if (leading) 12.dp else 0.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    content = content,
  )
}
