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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.SessionRepository
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.feature.group.list.GroupListScreen
import dev.ohs.trustdemo.onesa.feature.group.list.LocalSelectedGroupId
import dev.ohs.trustdemo.onesa.feature.group.profile.GroupProfileScreen
import dev.ohs.trustdemo.onesa.feature.patient.profile.PatientProfileScreen
import dev.ohs.trustdemo.onesa.feature.referral.ReferralInboxScreen
import dev.ohs.trustdemo.onesa.feature.sync.SyncStatusContent
import dev.ohs.trustdemo.onesa.feature.sync.SyncStatusViewModel
import dev.ohs.trustdemo.onesa.feature.sync.syncAction
import dev.ohs.trustdemo.onesa.feature.sync.syncedName
import dev.ohs.trustdemo.onesa.feature.trustlist.TrustListScreen
import dev.ohs.trustdemo.onesa.feature.trustlist.TrustListViewModel
import dev.ohs.trustdemo.onesa.feature.wallet.WalletScreen
import dev.ohs.trustdemo.onesa.feature.wallet.WalletViewModel
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.home_cancel_sync
import dev.ohs.trustdemo.onesa.generated.resources.home_select_household
import dev.ohs.trustdemo.onesa.generated.resources.home_sign_out
import dev.ohs.trustdemo.onesa.generated.resources.home_signed_in_as
import dev.ohs.trustdemo.onesa.generated.resources.home_sync_cancelled
import dev.ohs.trustdemo.onesa.generated.resources.home_sync_failed
import dev.ohs.trustdemo.onesa.generated.resources.home_sync_now
import dev.ohs.trustdemo.onesa.generated.resources.shell_chw_subtitle
import dev.ohs.trustdemo.onesa.generated.resources.shell_chw_subtitle_no_place
import dev.ohs.trustdemo.onesa.generated.resources.shell_chw_title
import dev.ohs.trustdemo.onesa.generated.resources.shell_clinic_subtitle
import dev.ohs.trustdemo.onesa.generated.resources.shell_clinic_title
import dev.ohs.trustdemo.onesa.generated.resources.shell_clinic_title_no_place
import dev.ohs.trustdemo.onesa.generated.resources.shell_family_subtitle
import dev.ohs.trustdemo.onesa.generated.resources.shell_family_title
import dev.ohs.trustdemo.onesa.generated.resources.shell_not_synced
import dev.ohs.trustdemo.onesa.generated.resources.shell_offline
import dev.ohs.trustdemo.onesa.generated.resources.shell_portal_connected
import dev.ohs.trustdemo.onesa.generated.resources.shell_synced
import dev.ohs.trustdemo.onesa.generated.resources.shell_trust_cached
import dev.ohs.trustdemo.onesa.generated.resources.shell_trust_stale
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeScreen(
  userName: String,
  role: AppRole,
  onGroupClick: (String) -> Unit,
  onDataCaptureClick: () -> Unit,
  onAddMembers: (String) -> Unit,
  onAddClinicalData: (String) -> Unit,
  onSignOut: () -> Unit,
  onOpenReferral: (String) -> Unit = {},
  onShareRecords: () -> Unit = {},
  onSharedLinks: () -> Unit = {},
) {
  val homeViewModel: HomeViewModel = koinViewModel()
  val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
  val statusViewModel: SyncStatusViewModel = koinViewModel()
  val pending by statusViewModel.pending.collectAsStateWithLifecycle()
  LaunchedEffect(uiState.isSyncing) { if (!uiState.isSyncing) statusViewModel.refresh() }

  val destinations = remember(role) { role.destinations() }
  var selected by rememberSaveable { mutableIntStateOf(0) }
  val destination = destinations[selected.coerceIn(destinations.indices)]
  var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
  var selectedPatientId by rememberSaveable(selectedGroupId) { mutableStateOf<String?>(null) }
  val snackbarHostState = remember { SnackbarHostState() }

  val syncErrorMessage =
    when (uiState.syncError) {
      SyncError.Failed -> stringResource(Res.string.home_sync_failed)
      SyncError.Cancelled -> stringResource(Res.string.home_sync_cancelled)
      null -> null
    }
  LaunchedEffect(uiState.syncError) {
    if (syncErrorMessage != null) {
      snackbarHostState.showSnackbar(syncErrorMessage)
      homeViewModel.clearSyncError()
    }
  }

  val isExpandedWidth =
    currentWindowAdaptiveInfo()
      .windowSizeClass
      .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
  val onSyncNow = { homeViewModel.syncNow() }
  val onCancelSync = { homeViewModel.cancelSync() }

  HomeShell(
    header = shellHeader(role, userName, uiState.lastSyncedAt),
    tabs = destinations.map { stringResource(it.label) },
    selected = destinations.indexOf(destination),
    onSelect = { selected = it },
    menu = { close ->
      DropdownMenuItem(
        text = { Text(stringResource(Res.string.home_signed_in_as, userName)) },
        onClick = {},
        enabled = false,
      )
      DropdownMenuItem(
        text = {
          Text(
            stringResource(
              if (uiState.isSyncing) Res.string.home_cancel_sync else Res.string.home_sync_now
            )
          )
        },
        onClick = {
          close()
          if (uiState.isSyncing) onCancelSync() else onSyncNow()
        },
      )
      DropdownMenuItem(
        text = { Text(stringResource(Res.string.home_sign_out)) },
        onClick = {
          close()
          onSignOut()
        },
      )
    },
    actions =
      if (destination == HomeDestination.Sync) {
        listOf(syncAction(uiState.isSyncing, { onSyncNow() }, onCancelSync))
      } else null,
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) {
    when (destination) {
      HomeDestination.Households ->
        if (isExpandedWidth) {
          Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
              CompositionLocalProvider(LocalSelectedGroupId provides selectedGroupId) {
                GroupListScreen(
                  role = role,
                  onGroupClick = { selectedGroupId = it },
                  onDataCaptureClick = onDataCaptureClick,
                )
              }
            }
            VerticalDivider(color = OnesaColors.Line)
            Box(modifier = Modifier.weight(1.5f).fillMaxSize()) {
              val patientId = selectedPatientId
              val groupId = selectedGroupId
              when {
                patientId != null ->
                  PatientProfileScreen(
                    patientId = patientId,
                    role = role,
                    onBack = { selectedPatientId = null },
                    onAddClinicalData = { onAddClinicalData(patientId) },
                  )
                groupId != null ->
                  GroupProfileScreen(
                    groupId = groupId,
                    role = role,
                    onBack = { selectedGroupId = null },
                    onMemberClick = { selectedPatientId = it },
                    onAddMembers = { onAddMembers(groupId) },
                  )
                else -> EmptyDetailPlaceholder()
              }
            }
          }
        } else {
          GroupListScreen(
            role = role,
            onGroupClick = onGroupClick,
            onDataCaptureClick = onDataCaptureClick,
          )
        }
      HomeDestination.Referrals -> ReferralInboxScreen(onOpen = onOpenReferral)
      HomeDestination.TrustLists -> TrustListScreen()
      HomeDestination.Wallet ->
        WalletScreen(onShare = onShareRecords, onSharedLinks = onSharedLinks)
      HomeDestination.Sync -> SyncStatusContent(pending, uiState.lastSyncedAt, role)
    }
  }
}

/** The role's header: who is signed in, where, and how fresh the phone's records are. */
@Composable
private fun shellHeader(role: AppRole, userName: String, lastSync: Instant?): ShellHeader {
  val repository: FhirRepository = koinInject()
  val session by SessionRepository.session.collectAsStateWithLifecycle()
  val user = session?.context
  val place by
    produceState<String?>(null, user) {
      value =
        user?.locationId?.let { repository.get("Location", it) }?.let(::syncedName)
          ?: user?.organizationId?.let { repository.get("Organization", it) }?.let(::syncedName)
    }
  val synced = syncChip(lastSync)
  return when (role) {
    AppRole.CHW ->
      ShellHeader(
        title = stringResource(Res.string.shell_chw_title),
        icon = OnesaIcons.Users,
        subtitle =
          place?.let { stringResource(Res.string.shell_chw_subtitle, it, userName) }
            ?: stringResource(Res.string.shell_chw_subtitle_no_place, userName),
        status = synced,
      )
    AppRole.CLINICIAN -> {
      val trustViewModel: TrustListViewModel = koinViewModel()
      val lists by trustViewModel.lists.collectAsStateWithLifecycle()
      LaunchedEffect(Unit) { trustViewModel.load() }
      val list = lists?.firstOrNull()
      ShellHeader(
        title =
          place?.let { stringResource(Res.string.shell_clinic_title, it) }
            ?: stringResource(Res.string.shell_clinic_title_no_place),
        icon = OnesaIcons.Building,
        subtitle = stringResource(Res.string.shell_clinic_subtitle, userName),
        chips =
          listOfNotNull(
            when {
              list == null -> null
              list.stale -> Status(stringResource(Res.string.shell_trust_stale), Tone.Warning)
              else ->
                list.fetchedAt?.let {
                  Status(stringResource(Res.string.shell_trust_cached, it), Tone.Success)
                }
            }
          ),
      )
    }
    AppRole.FAMILY -> {
      val walletViewModel: WalletViewModel = koinViewModel()
      val members by walletViewModel.members.collectAsStateWithLifecycle()
      val family =
        members?.mapNotNull { it.name?.substringAfterLast(' ') }?.distinct()?.singleOrNull()
      ShellHeader(
        title = stringResource(Res.string.shell_family_title),
        icon = OnesaIcons.Users,
        subtitle =
          members?.let {
            stringResource(Res.string.shell_family_subtitle, family ?: userName, it.size)
          },
        status =
          if (synced.tone == Tone.Success) {
            Status(stringResource(Res.string.shell_portal_connected), Tone.Success)
          } else synced,
      )
    }
  }
}

/** Synced today, or offline for some days, from the last successful sync. */
@Composable
private fun syncChip(lastSync: Instant?): Status {
  lastSync ?: return Status(stringResource(Res.string.shell_not_synced), Tone.Warning)
  val age = Clock.System.now() - lastSync
  return if (age < 1.days) {
    val local = lastSync.toLocalDateTime(TimeZone.currentSystemDefault())
    val time =
      "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    Status(stringResource(Res.string.shell_synced, time), Tone.Success)
  } else {
    Status(stringResource(Res.string.shell_offline, age.inWholeDays.toInt()), Tone.Warning)
  }
}

@Composable
private fun EmptyDetailPlaceholder() {
  Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Text(
      text = stringResource(Res.string.home_select_household),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}
