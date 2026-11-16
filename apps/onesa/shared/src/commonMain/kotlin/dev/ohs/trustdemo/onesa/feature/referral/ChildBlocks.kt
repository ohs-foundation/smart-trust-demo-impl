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
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ohs.trustdemo.onesa.data.repository.PatientContext
import dev.ohs.trustdemo.onesa.data.repository.displayAge
import dev.ohs.trustdemo.onesa.data.schedule.DisplayOnlyRow
import dev.ohs.trustdemo.onesa.data.schedule.Schedule
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleItem
import dev.ohs.trustdemo.onesa.data.schedule.Standing
import dev.ohs.trustdemo.onesa.data.verification.ForeignDose
import dev.ohs.trustdemo.onesa.data.verification.shortName
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.child_audit_logged
import dev.ohs.trustdemo.onesa.generated.resources.child_doses_verified
import dev.ohs.trustdemo.onesa.generated.resources.child_household_id
import dev.ohs.trustdemo.onesa.generated.resources.child_title
import dev.ohs.trustdemo.onesa.generated.resources.home_back
import dev.ohs.trustdemo.onesa.generated.resources.schedule_card_footnote
import dev.ohs.trustdemo.onesa.generated.resources.schedule_card_title
import dev.ohs.trustdemo.onesa.generated.resources.schedule_could_not
import dev.ohs.trustdemo.onesa.generated.resources.schedule_due_day
import dev.ohs.trustdemo.onesa.generated.resources.schedule_upcoming
import dev.ohs.trustdemo.onesa.generated.resources.schedule_verified
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.dayMonth
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * The header of a screen about one child: the name with the age (`Somsak Sengmany (4 Weeks Old)`),
 * the household id, and how many of the child's foreign doses are verified, with [chips] after.
 */
@Composable
fun ChildTopBar(
  child: PatientContext?,
  fallbackName: String,
  today: LocalDate,
  onBack: () -> Unit,
  doses: List<ForeignDose> = emptyList(),
  subtitle: String? = null,
  chips: List<Status> = emptyList(),
) {
  val name = child?.name ?: fallbackName
  OnesaTopBar(
    title =
      displayAge(child?.birthDate, today)?.let { stringResource(Res.string.child_title, name, it) }
        ?: name,
    icon = OnesaIcons.Back,
    subtitle =
      subtitle ?: child?.householdId?.let { stringResource(Res.string.child_household_id, it) },
    chips = listOfNotNull(verifiedChip(doses)) + chips,
    onIconClick = onBack,
    iconLabel = stringResource(Res.string.home_back),
  )
}

/** "2 Foreign Doses Cryptographically Verified • Provenance Attached", for the doses that are. */
@Composable
fun verifiedChip(doses: List<ForeignDose>, icon: ImageVector? = null): Status? =
  doses
    .count { it.verified }
    .takeIf { it > 0 }
    ?.let { Status(stringResource(Res.string.child_doses_verified, it), Tone.Success, icon) }

/** "AuditEvent Logged at OHS Info Gateway", once a scan of the child's cards has been audited. */
@Composable
fun auditChip(child: PatientContext?): Status? =
  child
    ?.scanAudits
    ?.takeIf { it > 0 }
    ?.let { Status(stringResource(Res.string.child_audit_logged), Tone.Info) }

/**
 * The national schedule as evaluated for the child, one row per age: its doses, and whether they
 * are verified, due, upcoming or could not be evaluated. The [upcoming] display-only doses join the
 * row of their age, or make one of their own, in age order; they are never evaluated.
 */
@Composable
fun ScheduleCard(schedule: Schedule, upcoming: List<DisplayOnlyRow> = emptyList()) {
  val evaluated = schedule.items.groupBy { it.age }.map { (age, items) -> Age(age, items) }
  val headings = evaluated.associateBy { it.heading }
  val extra = upcoming.groupBy { it.label }
  val rows =
    (evaluated.map { it.heading to it.items.mapNotNull(ScheduleItem::dueDate).minOrNull() } +
        upcoming.filter { it.label !in headings }.map { it.label to it.due(schedule.birthDate) })
      .distinctBy { it.first }
      .sortedBy { it.second }
  SectionCard(
    title = stringResource(Res.string.schedule_card_title),
    icon = OnesaIcons.Calendar,
    footnote = stringResource(Res.string.schedule_card_footnote),
  ) {
    rows.forEach { (heading, _) ->
      val items = headings[heading]?.items.orEmpty()
      val due = items.mapNotNull { (it.standing as? Standing.Due)?.date }.minOrNull()
      RecordRow(
        label = heading.ifEmpty { items.first().title },
        code =
          (items.filter { it.dose.code.isNotEmpty() }.map { it.dose.shortName } +
              extra[heading].orEmpty().flatMap { it.doses })
            .joinToString(),
        status =
          when {
            items.isEmpty() -> Status(stringResource(Res.string.schedule_upcoming))
            items.any { it.standing is Standing.CouldNotEvaluate } ->
              Status(stringResource(Res.string.schedule_could_not), Tone.Danger)
            items.all { it.standing == Standing.Complete } ->
              Status(stringResource(Res.string.schedule_verified), Tone.Success, OnesaIcons.Check)
            due != null ->
              Status(stringResource(Res.string.schedule_due_day, dayMonth(due)), Tone.Warning)
            else -> Status(stringResource(Res.string.schedule_upcoming))
          },
      )
    }
  }
}

private class Age(age: String, val items: List<ScheduleItem>) {
  val heading = ageHeading(age)
}

/** A schedule age as a row heading: `Birth`, `6-week` as `6 Weeks`, `9-month` as `9 Months`. */
private fun ageHeading(age: String): String {
  val count = age.substringBefore('-').toIntOrNull() ?: return age
  val unit = age.substringAfter('-').replaceFirstChar(Char::uppercaseChar)
  return "$count $unit${if (count == 1) "" else "s"}"
}
