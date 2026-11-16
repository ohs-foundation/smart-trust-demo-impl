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
package dev.ohs.trustdemo.onesa.feature.patient.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.registry.layoutRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.SessionRepository
import dev.ohs.trustdemo.onesa.data.repository.PatientContext
import dev.ohs.trustdemo.onesa.data.schedule.BookedVisit
import dev.ohs.trustdemo.onesa.data.verification.shortDoseName
import dev.ohs.trustdemo.onesa.feature.home.RoleTopBar
import dev.ohs.trustdemo.onesa.feature.role.ScreenAction
import dev.ohs.trustdemo.onesa.feature.role.actions
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.list_stale
import dev.ohs.trustdemo.onesa.generated.resources.patient_profile_add_clinical_data
import dev.ohs.trustdemo.onesa.generated.resources.patient_profile_default_name
import dev.ohs.trustdemo.onesa.generated.resources.patient_profile_not_found
import dev.ohs.trustdemo.onesa.generated.resources.profile_booked_visit
import dev.ohs.trustdemo.onesa.generated.resources.profile_born
import dev.ohs.trustdemo.onesa.generated.resources.profile_due_day
import dev.ohs.trustdemo.onesa.generated.resources.profile_household_chip
import dev.ohs.trustdemo.onesa.generated.resources.profile_immunizations
import dev.ohs.trustdemo.onesa.generated.resources.profile_next_doses
import dev.ohs.trustdemo.onesa.generated.resources.profile_no_doses
import dev.ohs.trustdemo.onesa.generated.resources.profile_no_next_doses
import dev.ohs.trustdemo.onesa.generated.resources.profile_no_visit
import dev.ohs.trustdemo.onesa.generated.resources.profile_record_dose
import dev.ohs.trustdemo.onesa.generated.resources.profile_referral_completed
import dev.ohs.trustdemo.onesa.generated.resources.profile_referral_open
import dev.ohs.trustdemo.onesa.generated.resources.profile_referral_withdrawn
import dev.ohs.trustdemo.onesa.generated.resources.profile_reminder
import dev.ohs.trustdemo.onesa.generated.resources.profile_removed_many
import dev.ohs.trustdemo.onesa.generated.resources.profile_removed_one
import dev.ohs.trustdemo.onesa.generated.resources.profile_tab_doses
import dev.ohs.trustdemo.onesa.generated.resources.profile_tab_household
import dev.ohs.trustdemo.onesa.generated.resources.profile_tab_overview
import dev.ohs.trustdemo.onesa.generated.resources.profile_tab_referrals
import dev.ohs.trustdemo.onesa.generated.resources.profile_tab_schedule
import dev.ohs.trustdemo.onesa.generated.resources.profile_temporary_id
import dev.ohs.trustdemo.onesa.generated.resources.profile_verified_count
import dev.ohs.trustdemo.onesa.generated.resources.profile_window
import dev.ohs.trustdemo.onesa.generated.state.AllergyReactionState
import dev.ohs.trustdemo.onesa.generated.state.PatientAllergyState
import dev.ohs.trustdemo.onesa.generated.state.PatientConditionState
import dev.ohs.trustdemo.onesa.generated.state.PatientContactState
import dev.ohs.trustdemo.onesa.generated.state.PatientImmunizationState
import dev.ohs.trustdemo.onesa.generated.state.PatientMedicationState
import dev.ohs.trustdemo.onesa.generated.state.PatientTelecomState
import dev.ohs.trustdemo.onesa.generated.viewtype.ViewTypeCS
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.BackHandler
import dev.ohs.trustdemo.onesa.ui.ChildAvatar
import dev.ohs.trustdemo.onesa.ui.Field
import dev.ohs.trustdemo.onesa.ui.FieldGrid
import dev.ohs.trustdemo.onesa.ui.NoteLine
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.PatientBanner
import dev.ohs.trustdemo.onesa.ui.PillTabs
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.dayMonth
import dev.ohs.trustdemo.onesa.util.displayRange
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.monthsUntil
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The profile's tabs, in order. */
enum class ProfileTab {
  Overview,
  Doses,
  Schedule,
  Referrals,
  Household,
}

/**
 * A patient's profile (1-14): the banner, the tabs and, beside them, the next doses and the booked
 * visit. A clinician records a dose from the Doses tab, on its own screen (1-16). [showHeader] is
 * false where Home's header already shows; [onOpenPatient] opens a household member.
 */
@Composable
fun PatientProfileScreen(
  patientId: String,
  role: AppRole,
  onBack: () -> Unit,
  onAddClinicalData: () -> Unit,
  showHeader: Boolean = true,
  onOpenPatient: (String) -> Unit = {},
) {
  val viewModel =
    koinViewModel<PatientProfileViewModel>(key = patientId) { parametersOf(patientId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val form by viewModel.form.collectAsStateWithLifecycle()
  val recordedStale by viewModel.recordedStale.collectAsStateWithLifecycle()
  val session by SessionRepository.session.collectAsStateWithLifecycle()
  val canRecordDose = ScreenAction.RecordDose in role.actions()
  var tab by rememberSaveable { mutableIntStateOf(0) }
  val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

  val s = state
  if (s?.patient == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      if (s == null) CircularProgressIndicator()
      else Text(stringResource(Res.string.patient_profile_not_found))
    }
    return
  }
  form?.let { open ->
    BackHandler(onBack = viewModel::closeForm)
    RecordDoseContent(
      form = open,
      state = s,
      givenBy = session?.user?.let { it.fullName.ifBlank { it.username } },
      today = today,
      onBack = viewModel::closeForm,
      onPickDose = viewModel::pickDose,
      onPickProduct = viewModel::pickProduct,
      onLot = viewModel::typeLot,
      onDate = viewModel::typeDate,
      onSave = viewModel::record,
    )
    return
  }
  val selected = ProfileTab.entries[tab]
  PatientProfileContent(
    state = s,
    today = today,
    tab = selected,
    onTab = { tab = it.ordinal },
    topBar = { if (showHeader) RoleTopBar(role, onBack) },
    actions =
      when {
        !canRecordDose -> emptyList()
        selected == ProfileTab.Doses ->
          listOf(Action(stringResource(Res.string.profile_record_dose), viewModel::openForm))
        selected == ProfileTab.Overview ->
          listOf(
            Action(
              stringResource(Res.string.patient_profile_add_clinical_data),
              onAddClinicalData,
              primary = false,
            )
          )
        else -> emptyList()
      },
    recordedStale = recordedStale,
    schedule = { PatientScheduleTab(patientId) },
    referrals = { PatientReferralsTab(patientId) },
    household = { PatientHouseholdTab(s.context?.groupId, onOpenPatient) },
  )
}

@Composable
fun PatientProfileContent(
  state: ProfileUiState,
  today: LocalDate,
  tab: ProfileTab,
  onTab: (ProfileTab) -> Unit,
  topBar: @Composable () -> Unit,
  actions: List<Action>,
  recordedStale: Boolean = false,
  schedule: @Composable () -> Unit = {},
  referrals: @Composable () -> Unit = {},
  household: @Composable () -> Unit = {},
) {
  val patient = state.patient
  val context = state.context
  val name =
    context?.name
      ?: listOfNotNull(patient?.givenName, patient?.familyName).joinToString(" ").ifBlank {
        stringResource(Res.string.patient_profile_default_name)
      }
  val child =
    context?.birthDate?.let { born ->
      runCatching { LocalDate.parse(born.take(10)).monthsUntil(today) < CHILD_MONTHS }.getOrNull()
    } == true
  val tabs =
    listOf(
      Res.string.profile_tab_overview,
      Res.string.profile_tab_doses,
      Res.string.profile_tab_schedule,
      Res.string.profile_tab_referrals,
      Res.string.profile_tab_household,
    )
  OnesaScaffold(
    topBar = topBar,
    banner = {
      PatientBanner(
        name = name,
        chips = listOfNotNull(householdChip(context), referralChip(context?.referralStatus)),
        subtitle = subtitle(context)?.ifEmpty { null },
        avatar = if (child) OnesaIcons.Baby else OnesaIcons.User,
        avatarColor = if (child) ChildAvatar else OnesaColors.Slate500,
      )
    },
    side = {
      NextDoses(state.visit, today)
      Visit(state.visit)
    },
    actions = actions.takeIf { it.isNotEmpty() }?.let { { PrimaryActionBar(it) } },
  ) {
    PillTabs(tabs.map { stringResource(it) }, tab.ordinal, { onTab(ProfileTab.entries[it]) })
    if (recordedStale) NoteLine(stringResource(Res.string.list_stale))
    when (tab) {
      ProfileTab.Overview -> Overview(state)
      ProfileTab.Doses -> Doses(state)
      ProfileTab.Schedule -> schedule()
      ProfileTab.Referrals -> referrals()
      ProfileTab.Household -> household()
    }
  }
}

@Composable
private fun Overview(state: ProfileUiState) {
  val registry = LocalViewRegistry.current
  val allergySection =
    remember(registry) { registry.layoutRenderer<PatientAllergyState>(ViewTypeCS.SectionCard) }
  val allergyRenderer =
    remember(registry) { registry.componentRenderer<PatientAllergyState>(ViewTypeCS.AllergyItem) }
  val reactionSection =
    remember(registry) { registry.layoutRenderer<AllergyReactionState>(ViewTypeCS.SectionCard) }
  val reactionRenderer =
    remember(registry) {
      registry.componentRenderer<AllergyReactionState>(ViewTypeCS.AllergyReactionItem)
    }
  val medicationSection =
    remember(registry) { registry.layoutRenderer<PatientMedicationState>(ViewTypeCS.SectionCard) }
  val medicationRenderer =
    remember(registry) {
      registry.componentRenderer<PatientMedicationState>(ViewTypeCS.MedicationItem)
    }
  val conditionSection =
    remember(registry) { registry.layoutRenderer<PatientConditionState>(ViewTypeCS.SectionCard) }
  val conditionRenderer =
    remember(registry) {
      registry.componentRenderer<PatientConditionState>(ViewTypeCS.ConditionItem)
    }
  val immunizationSection =
    remember(registry) { registry.layoutRenderer<PatientImmunizationState>(ViewTypeCS.SectionCard) }
  val immunizationRenderer =
    remember(registry) {
      registry.componentRenderer<PatientImmunizationState>(ViewTypeCS.ImmunizationItem)
    }
  val telecomSection =
    remember(registry) { registry.layoutRenderer<PatientTelecomState>(ViewTypeCS.SectionCard) }
  val telecomRenderer =
    remember(registry) { registry.componentRenderer<PatientTelecomState>(ViewTypeCS.TelecomItem) }
  val contactSection =
    remember(registry) { registry.layoutRenderer<PatientContactState>(ViewTypeCS.SectionCard) }
  val contactRenderer =
    remember(registry) { registry.componentRenderer<PatientContactState>(ViewTypeCS.ContactItem) }

  if (state.allergies.isNotEmpty()) {
    allergySection.Render(items = state.allergies, component = allergyRenderer, onItemClick = {})
  }
  if (state.allergyReactions.isNotEmpty()) {
    reactionSection.Render(
      items = state.allergyReactions,
      component = reactionRenderer,
      onItemClick = {},
    )
  }
  if (state.medications.isNotEmpty()) {
    medicationSection.Render(
      items = state.medications,
      component = medicationRenderer,
      onItemClick = {},
    )
  }
  if (state.conditions.isNotEmpty()) {
    conditionSection.Render(
      items = state.conditions,
      component = conditionRenderer,
      onItemClick = {},
    )
  }
  if (state.immunizations.isNotEmpty()) {
    immunizationSection.Render(
      items = state.immunizations,
      component = immunizationRenderer,
      onItemClick = {},
    )
  }
  if (state.telecoms.isNotEmpty()) {
    telecomSection.Render(items = state.telecoms, component = telecomRenderer, onItemClick = {})
  }
  if (state.contacts.isNotEmpty()) {
    contactSection.Render(items = state.contacts, component = contactRenderer, onItemClick = {})
  }
}

@Composable
private fun Doses(state: ProfileUiState) {
  val registry = LocalViewRegistry.current
  val renderer =
    remember(registry) {
      registry.componentRenderer<PatientImmunizationState>(ViewTypeCS.ImmunizationItem)
    }
  val verified = state.immunizations.count { it.verified == true }
  val removals = state.context?.removals.orEmpty()
  SectionCard(
    title = stringResource(Res.string.profile_immunizations),
    summary =
      Status(stringResource(Res.string.profile_verified_count, verified), Tone.Success).takeIf {
        verified > 0
      },
    footnote =
      when (removals.size) {
        0 -> null
        1 ->
          stringResource(
            Res.string.profile_removed_one,
            removals.values.single(),
            removals.keys.single(),
          )
        else ->
          stringResource(Res.string.profile_removed_many, removals.values.sum(), removals.size)
      },
  ) {
    if (state.immunizations.isEmpty()) {
      Text(
        stringResource(Res.string.profile_no_doses),
        style = MaterialTheme.typography.bodyMedium,
        color = OnesaColors.Muted,
      )
    }
    state.immunizations.forEach { renderer.Render(it, RenderOptions()) }
  }
}

@Composable
private fun NextDoses(visit: BookedVisit?, today: LocalDate) {
  SectionCard(title = stringResource(Res.string.profile_next_doses)) {
    if (visit == null || visit.doses.isEmpty()) {
      Text(
        stringResource(Res.string.profile_no_next_doses),
        style = MaterialTheme.typography.bodyMedium,
        color = OnesaColors.Muted,
      )
    }
    visit?.doses?.forEach { dose ->
      val due = dose.due?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
      RecordRow(
        label = shortDoseName(dose.name),
        code = dose.code,
        status =
          due?.let {
            Status(
              stringResource(Res.string.profile_due_day, dayMonth(it)),
              if (it < today) Tone.Danger else Tone.Warning,
            )
          },
      )
    }
  }
}

@Composable
private fun Visit(visit: BookedVisit?) {
  SectionCard(title = stringResource(Res.string.profile_booked_visit)) {
    val from = visit?.reminderFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val to = visit?.reminderTo?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (visit?.reminderId == null || from == null || to == null) {
      Text(
        stringResource(Res.string.profile_no_visit),
        style = MaterialTheme.typography.bodyMedium,
        color = OnesaColors.Muted,
      )
    } else {
      FieldGrid(
        listOf(
          Field(stringResource(Res.string.profile_window), displayRange(from, to)),
          Field(
            stringResource(Res.string.profile_reminder),
            "Task/${visit.reminderId}",
            mono = true,
          ),
        )
      )
    }
  }
}

@Composable
private fun householdChip(context: PatientContext?): Status? {
  val household = context?.household ?: return null
  val text =
    context.householdId?.let { stringResource(Res.string.profile_household_chip, household, it) }
      ?: household
  return Status(text, Tone.Info)
}

@Composable
private fun referralChip(status: String?): Status? =
  when (status) {
    "active" -> Status(stringResource(Res.string.profile_referral_open), Tone.Warning)
    "completed" -> Status(stringResource(Res.string.profile_referral_completed), Tone.Success)
    "revoked",
    "entered-in-error" -> Status(stringResource(Res.string.profile_referral_withdrawn))
    else -> null
  }

@Composable
private fun subtitle(context: PatientContext?): String? =
  context?.let {
    listOfNotNull(
        it.gender?.replaceFirstChar(Char::uppercaseChar),
        it.birthDate?.let { born -> stringResource(Res.string.profile_born, born) },
        it.temporaryId?.let { id -> stringResource(Res.string.profile_temporary_id, id) },
      )
      .joinToString(" • ")
  }

private const val CHILD_MONTHS = 60
