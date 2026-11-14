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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.data.sharing.IssuedLink
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.issued_close
import dev.ohs.trustdemo.onesa.generated.resources.issued_expires
import dev.ohs.trustdemo.onesa.generated.resources.issued_next
import dev.ohs.trustdemo.onesa.generated.resources.issued_not_saved
import dev.ohs.trustdemo.onesa.generated.resources.issued_passcode_hint
import dev.ohs.trustdemo.onesa.generated.resources.issued_previous
import dev.ohs.trustdemo.onesa.generated.resources.issued_qr_of
import dev.ohs.trustdemo.onesa.generated.resources.issued_show_full_screen
import dev.ohs.trustdemo.onesa.generated.resources.issued_title
import dev.ohs.trustdemo.onesa.generated.resources.share_back
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** S7: one QR per person shared, each to scan at the clinic, and one at a time in full screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuedLinksScreen(consentIds: List<String>, onBack: () -> Unit) {
  val viewModel: IssuedLinksViewModel = koinViewModel { parametersOf(consentIds) }
  val links by viewModel.links.collectAsStateWithLifecycle()
  var fullScreen by remember { mutableStateOf<Int?>(null) }

  val unsaved by viewModel.unsaved.collectAsStateWithLifecycle()
  KeepOffScreenshots()
  val issued = links
  val shown = fullScreen
  if (issued != null && shown != null) {
    FullScreenQr(
      links = issued,
      index = shown,
      onIndex = { fullScreen = it },
      onClose = { fullScreen = null },
    )
    return
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.issued_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(Res.string.share_back),
            )
          }
        },
      )
    }
  ) { padding ->
    if (issued == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return@Scaffold
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(Res.string.issued_passcode_hint),
            style = MaterialTheme.typography.bodyMedium,
          )
          if (unsaved) {
            Text(
              stringResource(Res.string.issued_not_saved),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          Button(
            onClick = { fullScreen = 0 },
            enabled = issued.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(stringResource(Res.string.issued_show_full_screen))
          }
        }
      }
      items(issued, key = { it.consentId }) { link ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          QrImage(link, Modifier.size(140.dp))
          Column {
            Text(link.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.issued_expires, link.expiresOn()))
          }
        }
      }
    }
  }
}

@Composable
private fun FullScreenQr(
  links: List<IssuedLink>,
  index: Int,
  onIndex: (Int) -> Unit,
  onClose: () -> Unit,
) {
  val link = links[index]
  Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        stringResource(Res.string.issued_qr_of, index + 1, links.size),
        style = MaterialTheme.typography.labelLarge,
        color = Color.Black,
      )
      Text(
        link.name,
        style = MaterialTheme.typography.headlineSmall,
        color = Color.Black,
        textAlign = TextAlign.Center,
      )
      Text(stringResource(Res.string.issued_expires, link.expiresOn()), color = Color.Black)
      QrImage(link, Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = true))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onIndex(index - 1) }, enabled = index > 0) {
          Text(stringResource(Res.string.issued_previous))
        }
        OutlinedButton(onClick = { onIndex(index + 1) }, enabled = index < links.lastIndex) {
          Text(stringResource(Res.string.issued_next))
        }
        Button(onClick = onClose) { Text(stringResource(Res.string.issued_close)) }
      }
    }
  }
}

@Composable
private fun QrImage(link: IssuedLink, modifier: Modifier) {
  Image(
    painter = rememberQrCodePainter(link.link),
    contentDescription = link.name,
    modifier = modifier.background(Color.White).padding(8.dp),
  )
}

private fun IssuedLink.expiresOn(): String =
  Instant.fromEpochSeconds(exp - 1).toLocalDateTime(TimeZone.UTC).date.toString() + " (UTC)"
