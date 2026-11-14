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
package dev.ohs.trustdemo.onesa.feature.referral

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleDose
import dev.ohs.trustdemo.onesa.data.schedule.Standing
import dev.ohs.trustdemo.onesa.data.verification.ForeignDose
import dev.ohs.trustdemo.onesa.data.verification.shortName
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.adjudication_code
import dev.ohs.trustdemo.onesa.generated.resources.adjudication_detail
import dev.ohs.trustdemo.onesa.generated.resources.adjudication_match
import dev.ohs.trustdemo.onesa.generated.resources.adjudication_title
import dev.ohs.trustdemo.onesa.generated.resources.adjudication_verdict
import dev.ohs.trustdemo.onesa.generated.resources.booking_booked
import dev.ohs.trustdemo.onesa.generated.resources.booking_date
import dev.ohs.trustdemo.onesa.generated.resources.booking_not_booked
import dev.ohs.trustdemo.onesa.generated.resources.booking_recommended
import dev.ohs.trustdemo.onesa.generated.resources.booking_recommended_value
import dev.ohs.trustdemo.onesa.generated.resources.booking_referral_done
import dev.ohs.trustdemo.onesa.generated.resources.booking_task
import dev.ohs.trustdemo.onesa.generated.resources.booking_title
import dev.ohs.trustdemo.onesa.generated.resources.layer1
import dev.ohs.trustdemo.onesa.generated.resources.layer1_chip
import dev.ohs.trustdemo.onesa.generated.resources.layer1_detail
import dev.ohs.trustdemo.onesa.generated.resources.layer2
import dev.ohs.trustdemo.onesa.generated.resources.layer2_chip
import dev.ohs.trustdemo.onesa.generated.resources.layer2_detail
import dev.ohs.trustdemo.onesa.generated.resources.layer3
import dev.ohs.trustdemo.onesa.generated.resources.layer3_confirmed
import dev.ohs.trustdemo.onesa.generated.resources.layer3_confirmed_detail
import dev.ohs.trustdemo.onesa.generated.resources.layer3_gap
import dev.ohs.trustdemo.onesa.generated.resources.layer3_gap_detail
import dev.ohs.trustdemo.onesa.generated.resources.layer3_mapped_detail
import dev.ohs.trustdemo.onesa.generated.resources.layers_footnote_gap
import dev.ohs.trustdemo.onesa.generated.resources.layers_title
import dev.ohs.trustdemo.onesa.generated.resources.list_stale
import dev.ohs.trustdemo.onesa.generated.resources.match_mapped
import dev.ohs.trustdemo.onesa.generated.resources.not_counted_title
import dev.ohs.trustdemo.onesa.generated.resources.schedule_book
import dev.ohs.trustdemo.onesa.generated.resources.schedule_book_age
import dev.ohs.trustdemo.onesa.generated.resources.schedule_book_caption
import dev.ohs.trustdemo.onesa.generated.resources.schedule_could_not_load
import dev.ohs.trustdemo.onesa.generated.resources.schedule_incomplete
import dev.ohs.trustdemo.onesa.generated.resources.schedule_nothing_due
import dev.ohs.trustdemo.onesa.generated.resources.schedule_referral_changed
import dev.ohs.trustdemo.onesa.generated.resources.schedule_reported
import dev.ohs.trustdemo.onesa.generated.resources.schedule_unmapped
import dev.ohs.trustdemo.onesa.generated.resources.schedule_unreadable
import dev.ohs.trustdemo.onesa.generated.resources.sync_first
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.Field
import dev.ohs.trustdemo.onesa.ui.FieldGrid
import dev.ohs.trustdemo.onesa.ui.NoteLine
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.VerificationResultCard
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.displayDate
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * S5: how each foreign dose reaches the national schedule, the schedule evaluated for the child,
 * and the booking of the visit it recommends.
 */
@Composable
fun ScheduleScreen(referralId: String, onBack: () -> Unit) {
  val viewModel = koinViewModel<ScheduleViewModel>(key = referralId) { parametersOf(referralId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
  ScheduleContent(state, today, onBack, viewModel::book)
}

@Composable
fun ScheduleContent(
  state: ScheduleUiState,
  today: LocalDate,
  onBack: () -> Unit,
  onBook: () -> Unit,
) {
  val schedule = state.schedule
  val due = schedule?.items.orEmpty().filter { it.standing is Standing.Due }
  OnesaScaffold(
    topBar = {
      ChildTopBar(
        child = state.child,
        fallbackName = state.referral?.patientName.orEmpty(),
        today = today,
        onBack = onBack,
        doses = state.doses,
        chips = listOfNotNull(auditChip(state.child)),
      )
    },
    sideWide = true,
    side = {
      schedule?.let { ScheduleCard(it) }
      SectionCard(title = stringResource(Res.string.booking_title), icon = OnesaIcons.Clock) {
        FieldGrid(
          listOfNotNull(
            schedule
              ?.takeIf { due.isNotEmpty() }
              ?.let {
                Field(
                  stringResource(Res.string.booking_recommended),
                  stringResource(
                    Res.string.booking_recommended_value,
                    displayDate(it.visit),
                    due.joinToString { item -> item.dose.shortName },
                  ),
                )
              },
            schedule
              ?.takeIf { due.isNotEmpty() }
              ?.let { Field(stringResource(Res.string.booking_date), it.visit.toString(), true) },
            Field(
              stringResource(Res.string.booking_task),
              stringResource(
                when {
                  state.booked != null -> Res.string.booking_booked
                  state.open != null && !state.isOpen -> Res.string.booking_referral_done
                  else -> Res.string.booking_not_booked
                }
              ),
            ),
          )
        )
        if (schedule?.let { it.complete && !it.hasDue } == true && state.booked == null) {
          NoteLine(stringResource(Res.string.schedule_nothing_due))
        }
      }
    },
    actions = {
      PrimaryActionBar(
        listOf(
          Action(
            due.lastOrNull()?.age?.let { stringResource(Res.string.schedule_book_age, it) }
              ?: stringResource(Res.string.schedule_book),
            onClick = onBook,
            busy = state.busy,
            enabled = state.canBook,
          )
        ),
        caption = stringResource(Res.string.schedule_book_caption),
      )
    },
  ) {
    state.doses.filter { it.adjudication != null || it.schedule == null }.forEach { Layers(it) }
    state.doses
      .filter { it.adjudication != null && it.schedule != null }
      .forEach { dose ->
        val coded = dose.schedule ?: return@forEach
        val adjudication = dose.adjudication ?: return@forEach
        VerificationResultCard(
          title = stringResource(Res.string.adjudication_title),
          tone = Tone.Success,
          verdict = stringResource(Res.string.adjudication_verdict),
          fields =
            listOf(
              Field(stringResource(Res.string.adjudication_match), coded.display),
              Field(stringResource(Res.string.adjudication_code), coded.code, mono = true),
            ),
          detail =
            stringResource(
              Res.string.adjudication_detail,
              adjudication.attester,
              adjudication.recorded?.take(10).orEmpty(),
            ),
        )
      }
    schedule?.let {
      val uncounted =
        doses(Res.string.schedule_reported, it.reported) +
          doses(Res.string.schedule_unmapped, it.unmapped) +
          doses(Res.string.schedule_unreadable, it.unreadable)
      if (uncounted.isNotEmpty()) {
        SectionCard(
          title = stringResource(Res.string.not_counted_title),
          summary = Status(uncounted.size.toString(), Tone.Warning),
        ) {
          uncounted.forEach { (why, dose) ->
            RecordRow(label = dose.label, status = Status(why, Tone.Warning))
          }
        }
      }
    }
    Messages(state)
  }
}

@Composable
private fun doses(
  why: StringResource,
  doses: List<ScheduleDose>,
): List<Pair<String, ScheduleDose>> {
  val text = stringResource(why)
  return doses.map { text to it }
}

/** The three codings of one foreign dose: as issued, its WHO vaccine type, and the national one. */
@Composable
private fun Layers(dose: ForeignDose) {
  val coded = dose.schedule
  val adjudication = dose.adjudication
  SectionCard(
    title = stringResource(Res.string.layers_title),
    icon = OnesaIcons.Layers,
    footnote = if (coded == null) stringResource(Res.string.layers_footnote_gap) else null,
  ) {
    RecordRow(
      label =
        stringResource(
          Res.string.layer1,
          dose.origin?.let(NewbornRegistration::countryName).orEmpty(),
        ),
      code = dose.product,
      detail = stringResource(Res.string.layer1_detail),
      status = Status(stringResource(Res.string.layer1_chip)),
    )
    RecordRow(
      label = stringResource(Res.string.layer2),
      code = dose.productName,
      detail = stringResource(Res.string.layer2_detail),
      status = Status(stringResource(Res.string.layer2_chip), Tone.Info),
    )
    RecordRow(
      label = stringResource(Res.string.layer3),
      code = coded?.code,
      detail =
        when {
          coded == null -> stringResource(Res.string.layer3_gap_detail, dose.product)
          adjudication != null ->
            stringResource(
              Res.string.layer3_confirmed_detail,
              adjudication.attester,
              adjudication.recorded?.take(10).orEmpty(),
            )
          else -> stringResource(Res.string.layer3_mapped_detail)
        },
      status =
        when {
          coded == null -> Status(stringResource(Res.string.layer3_gap), Tone.Warning)
          adjudication != null -> Status(stringResource(Res.string.layer3_confirmed), Tone.Success)
          else -> Status(stringResource(Res.string.match_mapped), Tone.Success)
        },
    )
  }
}

@Composable
private fun Messages(state: ScheduleUiState) {
  if (state.failed) {
    AlertCard(null, stringResource(Res.string.schedule_could_not_load), Tone.Danger)
  }
  if (state.syncFirst) AlertCard(null, stringResource(Res.string.sync_first), Tone.Warning)
  if (state.referralChanged) {
    AlertCard(null, stringResource(Res.string.schedule_referral_changed), Tone.Warning)
  }
  if (state.booked?.listStale == true) NoteLine(stringResource(Res.string.list_stale))
  if (state.schedule?.complete == false) {
    AlertCard(null, stringResource(Res.string.schedule_incomplete), Tone.Danger)
  }
}
