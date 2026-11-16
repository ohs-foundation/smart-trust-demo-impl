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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.data.sharing.FamilyMember
import dev.ohs.trustdemo.onesa.data.sharing.IssuedLink
import dev.ohs.trustdemo.onesa.data.sharing.MAX_WRONG_PASSCODES
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.issued_badge
import dev.ohs.trustdemo.onesa.generated.resources.issued_close
import dev.ohs.trustdemo.onesa.generated.resources.issued_consent_note
import dev.ohs.trustdemo.onesa.generated.resources.issued_cycle
import dev.ohs.trustdemo.onesa.generated.resources.issued_expires
import dev.ohs.trustdemo.onesa.generated.resources.issued_next
import dev.ohs.trustdemo.onesa.generated.resources.issued_not_saved
import dev.ohs.trustdemo.onesa.generated.resources.issued_passcode_hint
import dev.ohs.trustdemo.onesa.generated.resources.issued_passcode_text
import dev.ohs.trustdemo.onesa.generated.resources.issued_passcode_title
import dev.ohs.trustdemo.onesa.generated.resources.issued_previous
import dev.ohs.trustdemo.onesa.generated.resources.issued_qr_caption
import dev.ohs.trustdemo.onesa.generated.resources.issued_qr_of
import dev.ohs.trustdemo.onesa.generated.resources.issued_qr_title
import dev.ohs.trustdemo.onesa.generated.resources.issued_rate_limit
import dev.ohs.trustdemo.onesa.generated.resources.issued_rate_limit_value
import dev.ohs.trustdemo.onesa.generated.resources.issued_show_full_screen
import dev.ohs.trustdemo.onesa.generated.resources.issued_title
import dev.ohs.trustdemo.onesa.generated.resources.issued_traveller
import dev.ohs.trustdemo.onesa.generated.resources.issued_validity
import dev.ohs.trustdemo.onesa.generated.resources.issued_validity_value
import dev.ohs.trustdemo.onesa.generated.resources.share_back
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.BackHandler
import dev.ohs.trustdemo.onesa.ui.Field
import dev.ohs.trustdemo.onesa.ui.FieldGrid
import dev.ohs.trustdemo.onesa.ui.NoteLine
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.QrPanel
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.displayDate
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * S7: each traveller's link as a QR, one at a time, cycled from the header, and in full screen to
 * show the clinic. The passcode is never on screen: the holder says it.
 */
@Composable
fun IssuedLinksScreen(consentIds: List<String>, onBack: () -> Unit) {
  val viewModel: IssuedLinksViewModel = koinViewModel { parametersOf(consentIds) }
  val links by viewModel.links.collectAsStateWithLifecycle()
  val family by viewModel.family.collectAsStateWithLifecycle()
  val unsaved by viewModel.unsaved.collectAsStateWithLifecycle()
  var index by remember { mutableIntStateOf(0) }
  var fullScreen by remember { mutableStateOf(false) }
  val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
  KeepOffScreenshots()

  val issued = links
  if (issued == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }
  val shown = index.coerceIn(0, (issued.size - 1).coerceAtLeast(0))
  BackHandler(enabled = fullScreen) { fullScreen = false }
  if (fullScreen && issued.isNotEmpty()) {
    FullScreenQr(issued, shown, { index = it }, onClose = { fullScreen = false })
  } else {
    IssuedLinksContent(
      issued,
      family,
      shown,
      unsaved,
      today,
      onIndex = { index = it },
      onBack = onBack,
      onFullScreen = { fullScreen = true },
    )
  }
}

@Composable
internal fun IssuedLinksContent(
  links: List<IssuedLink>,
  family: List<FamilyMember>,
  index: Int,
  unsaved: Boolean,
  today: LocalDate,
  onIndex: (Int) -> Unit,
  onBack: () -> Unit,
  onFullScreen: () -> Unit,
) {
  val link = links.getOrNull(index)
  val member = family.firstOrNull { it.id == link?.personId }
  OnesaScaffold(
    topBar = {
      OnesaTopBar(
        title = link?.let { stringResource(Res.string.issued_title, it.name) } ?: "",
        icon = OnesaIcons.Back,
        subtitle =
          listOfNotNull(
              stringResource(Res.string.issued_traveller, index + 1, links.size),
              member?.let { relationLine(it, today) },
            )
            .joinToString(" • "),
        status =
          if (links.size > 1)
            Status(
              stringResource(Res.string.issued_cycle, index + 1, links.size),
              icon = OnesaIcons.Users,
            )
          else null,
        onIconClick = onBack,
        iconLabel = stringResource(Res.string.share_back),
        onStatusClick = { onIndex((index + 1) % links.size) },
      )
    },
    actions = {
      PrimaryActionBar(
        listOf(
          Action(
            stringResource(Res.string.issued_show_full_screen),
            onClick = onFullScreen,
            icon = OnesaIcons.Qr,
            enabled = link != null,
          )
        ),
        caption = stringResource(Res.string.issued_passcode_hint),
      )
    },
  ) {
    if (unsaved) AlertCard(null, stringResource(Res.string.issued_not_saved), Tone.Warning)
    link?.let {
      QrPanel(
        payload = it.link,
        title = stringResource(Res.string.issued_qr_title),
        label = it.name,
        badge =
          Status(stringResource(Res.string.issued_badge), Tone.Success, OnesaIcons.ShieldCheck),
        caption = stringResource(Res.string.issued_qr_caption),
      )
      SectionCard(
        title = stringResource(Res.string.issued_passcode_title),
        icon = OnesaIcons.Lock,
      ) {
        Text(
          stringResource(Res.string.issued_passcode_text),
          style = MaterialTheme.typography.bodySmall,
          color = OnesaColors.Slate500,
        )
      }
      SectionCard {
        val until = expiresOn(it)
        FieldGrid(
          listOf(
            Field(
              stringResource(Res.string.issued_validity),
              stringResource(
                Res.string.issued_validity_value,
                today.daysUntil(until),
                displayDate(until),
              ),
            ),
            Field(
              stringResource(Res.string.issued_rate_limit),
              stringResource(Res.string.issued_rate_limit_value, MAX_WRONG_PASSCODES),
            ),
          ),
          columns = 2,
        )
      }
      NoteLine(stringResource(Res.string.issued_consent_note), icon = OnesaIcons.ShieldCheck)
    }
  }
}

/** One traveller's QR filling the screen, for the clinic to scan, with the way to the next. */
@Composable
internal fun FullScreenQr(
  links: List<IssuedLink>,
  index: Int,
  onIndex: (Int) -> Unit,
  onClose: () -> Unit,
) {
  val link = links[index]
  Column(
    Modifier.fillMaxSize().background(Color.White).padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      stringResource(Res.string.issued_qr_of, index + 1, links.size),
      style = MaterialTheme.typography.labelLarge,
      color = OnesaColors.Muted,
    )
    Text(
      link.name,
      style = MaterialTheme.typography.titleLarge,
      color = OnesaColors.Ink,
      textAlign = TextAlign.Center,
    )
    Text(
      stringResource(Res.string.issued_expires, displayDate(expiresOn(link))),
      style = MaterialTheme.typography.bodyMedium,
      color = OnesaColors.Muted,
    )
    Image(
      painter = rememberQrCodePainter(link.link),
      contentDescription = link.name,
      modifier =
        Modifier.weight(1f)
          .aspectRatio(1f, matchHeightConstraintsFirst = true)
          .background(Color.White)
          .padding(8.dp),
    )
    PrimaryActionBar(
      listOf(
        Action(
          stringResource(Res.string.issued_previous),
          { onIndex(index - 1) },
          false,
          enabled = index > 0,
        ),
        Action(
          stringResource(Res.string.issued_next),
          { onIndex(index + 1) },
          false,
          enabled = index < links.lastIndex,
        ),
        Action(stringResource(Res.string.issued_close), onClose),
      )
    )
  }
}

/** The last day the link works, in UTC as its expiry is. */
private fun expiresOn(link: IssuedLink): LocalDate =
  Instant.fromEpochSeconds(link.exp - 1).toLocalDateTime(TimeZone.UTC).date
