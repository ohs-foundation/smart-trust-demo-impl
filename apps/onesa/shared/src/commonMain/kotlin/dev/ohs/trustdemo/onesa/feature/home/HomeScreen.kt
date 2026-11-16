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
import dev.ohs.fhir.engine.sync.FhirDataStore
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.SessionRepository
import dev.ohs.trustdemo.onesa.data.verification.Referral
import dev.ohs.trustdemo.onesa.feature.group.list.GroupListScreen
import dev.ohs.trustdemo.onesa.feature.group.list.LocalSelectedGroupId
import dev.ohs.trustdemo.onesa.feature.group.profile.GroupProfileScreen
import dev.ohs.trustdemo.onesa.feature.patient.list.PatientListScreen
import dev.ohs.trustdemo.onesa.feature.patient.profile.PatientProfileScreen
import dev.ohs.trustdemo.onesa.feature.referral.ClinicScheduleScreen
import dev.ohs.trustdemo.onesa.feature.referral.InboxToday
import dev.ohs.trustdemo.onesa.feature.referral.ReferralInboxContent
import dev.ohs.trustdemo.onesa.feature.referral.ReferralInboxViewModel
import dev.ohs.trustdemo.onesa.feature.referral.SentReferralsScreen
import dev.ohs.trustdemo.onesa.feature.role.ScreenAction
import dev.ohs.trustdemo.onesa.feature.role.actions
import dev.ohs.trustdemo.onesa.feature.sync.SyncStatusContent
import dev.ohs.trustdemo.onesa.feature.sync.SyncStatusViewModel
import dev.ohs.trustdemo.onesa.feature.sync.syncAction
import dev.ohs.trustdemo.onesa.feature.trustlist.TrustListScreen
import dev.ohs.trustdemo.onesa.feature.trustlist.TrustListSide
import dev.ohs.trustdemo.onesa.feature.trustlist.TrustListViewModel
import dev.ohs.trustdemo.onesa.feature.wallet.WalletScreen
import dev.ohs.trustdemo.onesa.feature.wallet.WalletViewModel
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.group_list_register_household
import dev.ohs.trustdemo.onesa.generated.resources.home_back
import dev.ohs.trustdemo.onesa.generated.resources.home_cancel_sync
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_referrals_count
import dev.ohs.trustdemo.onesa.generated.resources.home_select_household
import dev.ohs.trustdemo.onesa.generated.resources.home_sign_out
import dev.ohs.trustdemo.onesa.generated.resources.home_signed_in_as
import dev.ohs.trustdemo.onesa.generated.resources.home_sync_cancelled
import dev.ohs.trustdemo.onesa.generated.resources.home_sync_failed
import dev.ohs.trustdemo.onesa.generated.resources.home_sync_now
import dev.ohs.trustdemo.onesa.generated.resources.inbox_open
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
import dev.ohs.trustdemo.onesa.generated.resources.trust_refresh_now
import dev.ohs.trustdemo.onesa.generated.resources.wallet_caption
import dev.ohs.trustdemo.onesa.generated.resources.wallet_share
import dev.ohs.trustdemo.onesa.generated.resources.wallet_shared_links
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.BackHandler
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.timeAgo
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
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
  onRegisterNewborn: (String) -> Unit = {},
  onOpenReferral: (String) -> Unit = {},
  onShareRecords: () -> Unit = {},
  onSharedLinks: () -> Unit = {},
  onOpenPatient: (String) -> Unit = {},
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
  val referrals = inbox(HomeDestination.Referrals in destinations)
  var selectedReferral by rememberSaveable { mutableStateOf<String?>(null) }
  val openReferral =
    selectedReferral?.takeIf { id -> referrals.orEmpty().any { it.id == id } }
      ?: referrals?.firstOrNull()?.id
  val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

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
  val detailOpen =
    isExpandedWidth &&
      destination == HomeDestination.Households &&
      (selectedGroupId != null || selectedPatientId != null)
  val closeDetail = {
    if (selectedPatientId != null) selectedPatientId = null else selectedGroupId = null
  }
  BackHandler(enabled = detailOpen, onBack = closeDetail)

  HomeShell(
    header = shellHeader(role, userName, uiState.lastSyncedAt),
    tabs =
      destinations.map {
        if (it == HomeDestination.Referrals && referrals != null) {
          stringResource(Res.string.home_destination_referrals_count, referrals.size)
        } else stringResource(it.label)
      },
    selected = destinations.indexOf(destination),
    onSelect = { selected = it },
    onBack = if (detailOpen) closeDetail else null,
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
      when {
        destination == HomeDestination.Sync ->
          listOf(syncAction(uiState.isSyncing, { onSyncNow() }, onCancelSync))
        destination == HomeDestination.Referrals ->
          listOf(
            Action(
              stringResource(Res.string.inbox_open),
              onClick = { openReferral?.let(onOpenReferral) },
              enabled = openReferral != null,
            )
          )
        destination == HomeDestination.TrustLists -> listOf(refreshAction())
        destination == HomeDestination.Wallet -> walletActions(onShareRecords, onSharedLinks)
        destination == HomeDestination.Households &&
          ScreenAction.RegisterHousehold in role.actions() ->
          listOf(
            Action(
              stringResource(Res.string.group_list_register_household),
              onClick = onDataCaptureClick,
              primary = false,
            )
          )
        else -> null
      },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    caption =
      if (destination == HomeDestination.Wallet) stringResource(Res.string.wallet_caption)
      else null,
    side =
      when (destination) {
        HomeDestination.Referrals -> {
          { InboxToday(today, syncChip(uiState.lastSyncedAt).text) }
        }
        HomeDestination.TrustLists -> {
          { TrustListSide() }
        }
        else -> null
      },
  ) {
    when (destination) {
      HomeDestination.Households ->
        if (isExpandedWidth) {
          Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
              CompositionLocalProvider(LocalSelectedGroupId provides selectedGroupId) {
                GroupListScreen(onGroupClick = { selectedGroupId = it })
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
                    showHeader = false,
                    onOpenPatient = { selectedPatientId = it },
                  )
                groupId != null ->
                  GroupProfileScreen(
                    groupId = groupId,
                    role = role,
                    onBack = { selectedGroupId = null },
                    onMemberClick = { selectedPatientId = it },
                    onAddMembers = { onAddMembers(groupId) },
                    onRegisterNewborn = { onRegisterNewborn(groupId) },
                    showHeader = false,
                  )
                else -> EmptyDetailPlaceholder()
              }
            }
          }
        } else {
          GroupListScreen(onGroupClick = onGroupClick)
        }
      HomeDestination.Referrals ->
        ReferralInboxContent(
          referrals = referrals.orEmpty(),
          place = rememberPlace(),
          selected = openReferral,
          onSelect = { selectedReferral = it },
        )
      HomeDestination.SentReferrals -> SentReferralsScreen()
      HomeDestination.Patients -> PatientListScreen(onPatientClick = onOpenPatient)
      HomeDestination.Schedule -> ClinicScheduleScreen(onOpenPatient = onOpenPatient)
      HomeDestination.TrustLists -> TrustListScreen()
      HomeDestination.Wallet -> WalletScreen()
      HomeDestination.Sync -> SyncStatusContent(pending, uiState.lastSyncedAt, role)
    }
  }
}

/**
 * The role's header for a screen opened from Home; its tile is the back arrow, and [subtitle] says
 * what the screen is for in place of who and where.
 */
@Composable
fun RoleTopBar(role: AppRole, onBack: () -> Unit, subtitle: String? = null) {
  val session by SessionRepository.session.collectAsStateWithLifecycle()
  val userName =
    session
      ?.user
      ?.let { user -> user.fullName.ifBlank { user.username }.ifBlank { user.email } }
      .orEmpty()
  val header = shellHeader(role, userName, rememberLastSync())
  OnesaTopBar(
    title = header.title,
    icon = OnesaIcons.Back,
    subtitle = subtitle ?: header.subtitle,
    chips = header.chips,
    status = header.status,
    onIconClick = onBack,
    iconLabel = stringResource(Res.string.home_back),
  )
}

/** The role's header: who is signed in, where, and how fresh the phone's records are. */
@Composable
private fun shellHeader(role: AppRole, userName: String, lastSync: Instant?): ShellHeader {
  val place = rememberPlace()
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
                list.fetchedAtEpochSeconds?.let {
                  Status(
                    stringResource(
                      Res.string.shell_trust_cached,
                      timeAgo(it, Clock.System.now().epochSeconds),
                    ),
                    Tone.Success,
                  )
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

/** When this phone last synced, read once. */
@Composable
private fun rememberLastSync(): Instant? {
  val dataStore: FhirDataStore = koinInject()
  val lastSync by produceState<Instant?>(null) { value = dataStore.readLastSyncTimestamp() }
  return lastSync
}

/** The sync chip for a screen outside Home, such as a form. */
@Composable fun rememberSyncChip(): Status = syncChip(rememberLastSync())

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

/** The clinic's open referrals, read only for a role that has the inbox. */
@Composable
private fun inbox(enabled: Boolean): List<Referral>? {
  if (!enabled) return null
  val viewModel: ReferralInboxViewModel = koinViewModel()
  val referrals by viewModel.referrals.collectAsStateWithLifecycle()
  return referrals
}

/** S6's actions: a link for every member, and the shares already made. */
@Composable
private fun walletActions(onShare: () -> Unit, onSharedLinks: () -> Unit): List<Action> {
  val viewModel: WalletViewModel = koinViewModel()
  val members by viewModel.members.collectAsStateWithLifecycle()
  val count = members.orEmpty().count { it.name != null }
  return listOf(
    Action(
      stringResource(Res.string.wallet_share, count),
      onClick = onShare,
      icon = OnesaIcons.Qr,
      enabled = count > 0,
    ),
    Action(stringResource(Res.string.wallet_shared_links), onClick = onSharedLinks, primary = false),
  )
}

/** "Refresh Now" for the trust list tab: fetches the local list, with a spinner meanwhile. */
@Composable
private fun refreshAction(): Action {
  val viewModel: TrustListViewModel = koinViewModel()
  val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
  return Action(
    stringResource(Res.string.trust_refresh_now),
    onClick = viewModel::refresh,
    primary = false,
    busy = refreshing,
    enabled = !refreshing,
  )
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
