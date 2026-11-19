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
package dev.ohs.trustdemo.onesa.feature.questionnaire

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSummary
import dev.ohs.trustdemo.onesa.data.repository.personName
import dev.ohs.trustdemo.onesa.feature.group.profile.LocalHouseholdHead
import dev.ohs.trustdemo.onesa.feature.group.profile.MemberItemRow
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.group_profile_default_name
import dev.ohs.trustdemo.onesa.generated.resources.group_profile_footnote
import dev.ohs.trustdemo.onesa.generated.resources.group_profile_members
import dev.ohs.trustdemo.onesa.generated.resources.list_stale
import dev.ohs.trustdemo.onesa.generated.resources.registration_born_abroad
import dev.ohs.trustdemo.onesa.generated.resources.registration_born_in
import dev.ohs.trustdemo.onesa.generated.resources.registration_child_of
import dev.ohs.trustdemo.onesa.generated.resources.registration_done
import dev.ohs.trustdemo.onesa.generated.resources.registration_doses_text
import dev.ohs.trustdemo.onesa.generated.resources.registration_doses_title
import dev.ohs.trustdemo.onesa.generated.resources.registration_field_birth_date
import dev.ohs.trustdemo.onesa.generated.resources.registration_field_birthplace
import dev.ohs.trustdemo.onesa.generated.resources.registration_field_family
import dev.ohs.trustdemo.onesa.generated.resources.registration_field_given
import dev.ohs.trustdemo.onesa.generated.resources.registration_field_relationship
import dev.ohs.trustdemo.onesa.generated.resources.registration_field_sex
import dev.ohs.trustdemo.onesa.generated.resources.registration_member_title
import dev.ohs.trustdemo.onesa.generated.resources.registration_no_national_id
import dev.ohs.trustdemo.onesa.generated.resources.registration_parent_father
import dev.ohs.trustdemo.onesa.generated.resources.registration_parent_mother
import dev.ohs.trustdemo.onesa.generated.resources.registration_priority_routine
import dev.ohs.trustdemo.onesa.generated.resources.registration_priority_urgent
import dev.ohs.trustdemo.onesa.generated.resources.registration_referral_priority
import dev.ohs.trustdemo.onesa.generated.resources.registration_referral_reason
import dev.ohs.trustdemo.onesa.generated.resources.registration_referral_target
import dev.ohs.trustdemo.onesa.generated.resources.registration_referral_title
import dev.ohs.trustdemo.onesa.generated.resources.registration_referred
import dev.ohs.trustdemo.onesa.generated.resources.registration_reported_text
import dev.ohs.trustdemo.onesa.generated.resources.registration_reported_title
import dev.ohs.trustdemo.onesa.generated.resources.registration_save
import dev.ohs.trustdemo.onesa.generated.resources.registration_save_all
import dev.ohs.trustdemo.onesa.generated.resources.registration_saved_text
import dev.ohs.trustdemo.onesa.generated.resources.registration_saved_title
import dev.ohs.trustdemo.onesa.generated.resources.registration_saved_waiting
import dev.ohs.trustdemo.onesa.generated.resources.registration_writes
import dev.ohs.trustdemo.onesa.ui.Action
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.Field
import dev.ohs.trustdemo.onesa.ui.FieldGrid
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaSelectField
import dev.ohs.trustdemo.onesa.ui.PrimaryActionBar
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource

/**
 * S3: the household, the newborn and the referral as the registration will write them, read back
 * from the transaction before it is sent. [onUrgent] sets the referral's priority.
 */
@Composable
fun RegistrationReviewContent(
  review: NewbornReview,
  topBar: @Composable () -> Unit,
  saving: Boolean,
  onSave: () -> Unit,
  onUrgent: (Boolean) -> Unit = {},
) {
  val records = review.review
  val country = records.birthCountry?.takeIf { it.isNotBlank() }
  val abroad = country != null && !country.equals(ONESA, ignoreCase = true)
  OnesaScaffold(
    topBar = topBar,
    actions = {
      PrimaryActionBar(
        listOf(
          Action(
            stringResource(
              if (records.referral != null) Res.string.registration_save_all
              else Res.string.registration_save
            ),
            onClick = onSave,
            icon = OnesaIcons.Send,
            enabled = !saving,
            busy = saving,
          )
        ),
        caption = stringResource(Res.string.registration_writes, writes(records.writes)),
      )
    },
  ) {
    val household = review.household
    SectionCard(
      title =
        household.groupHeader?.groupName ?: stringResource(Res.string.group_profile_default_name),
      icon = OnesaIcons.Users,
      summary =
        Status(stringResource(Res.string.group_profile_members, household.members.size), Tone.Info),
      footnote =
        household.groupHeader?.householdId?.let {
          stringResource(Res.string.group_profile_footnote, it)
        },
    ) {
      CompositionLocalProvider(LocalHouseholdHead provides household.headMemberId) {
        household.members.forEach { MemberItemRow(it, icon = OnesaIcons.User) }
      }
    }
    if (abroad) {
      AlertCard(
        title = stringResource(Res.string.registration_reported_title),
        text = stringResource(Res.string.registration_reported_text, country.orEmpty()),
        tone = Tone.Warning,
      )
    }
    val name = personName(records.given, records.family)
    SectionCard(
      title = stringResource(Res.string.registration_member_title),
      icon = OnesaIcons.Baby,
    ) {
      FieldGrid(
        listOf(
          Field(stringResource(Res.string.registration_field_given), records.given),
          Field(stringResource(Res.string.registration_field_family), records.family),
          Field(stringResource(Res.string.registration_field_birth_date), records.birthDate),
          Field(
            stringResource(Res.string.registration_field_sex),
            records.sex.replaceFirstChar { it.uppercaseChar() },
          ),
          Field(
            stringResource(Res.string.registration_field_relationship),
            stringResource(
              Res.string.registration_child_of,
              records.parents
                .map { parent ->
                  when (parent.role) {
                    "MTH" -> stringResource(Res.string.registration_parent_mother, parent.name)
                    "FTH" -> stringResource(Res.string.registration_parent_father, parent.name)
                    else -> parent.name
                  }
                }
                .joinToString(" and "),
            ),
          ),
          Field(
            stringResource(Res.string.registration_field_birthplace),
            listOfNotNull(
                when {
                  abroad -> stringResource(Res.string.registration_born_abroad, country.orEmpty())
                  country != null -> stringResource(Res.string.registration_born_in, country)
                  else -> null
                },
                if (records.hasNationalId) null
                else stringResource(Res.string.registration_no_national_id),
              )
              .joinToString(" • "),
          ),
        ),
        columns = 2,
      )
    }
    if (records.doses.isNotEmpty()) {
      AlertCard(
        title = stringResource(Res.string.registration_doses_title, records.doses.size),
        text =
          stringResource(
            Res.string.registration_doses_text,
            records.doses.joinToString(" & ") { NewbornRegistration.PRODUCTS[it] ?: it },
            name,
          ),
        tone = Tone.Info,
      )
    }
    records.referral?.let { referral ->
      SectionCard(
        title = stringResource(Res.string.registration_referral_title),
        icon = OnesaIcons.File,
      ) {
        FieldGrid(
          listOfNotNull(
            Field(
              stringResource(Res.string.registration_referral_target),
              review.referredTo ?: referral.performer.orEmpty(),
            ),
            referral.text?.let {
              Field(stringResource(Res.string.registration_referral_reason), it)
            },
          )
        )
        val priorities =
          listOf(
            stringResource(Res.string.registration_priority_routine),
            stringResource(Res.string.registration_priority_urgent),
          )
        val urgent = referral.priority == NewbornRegistration.URGENT
        OnesaSelectField(
          stringResource(Res.string.registration_referral_priority),
          priorities[if (urgent) 1 else 0],
          priorities,
          onSelect = { onUrgent(it == 1) },
        )
      }
    }
  }
}

/**
 * After the registration is saved: who was registered, whether it is in or waits on this phone, the
 * doses as reported, who was told.
 */
@Composable
fun RegistrationSavedContent(
  summary: RegistrationSummary,
  topBar: @Composable () -> Unit,
  onDone: () -> Unit,
) {
  OnesaScaffold(
    topBar = topBar,
    actions = {
      PrimaryActionBar(
        listOf(Action(stringResource(Res.string.registration_done), onClick = onDone))
      )
    },
  ) {
    AlertCard(
      title = stringResource(Res.string.registration_saved_title),
      text =
        listOfNotNull(
            stringResource(
              if (summary.waiting) Res.string.registration_saved_waiting
              else Res.string.registration_saved_text,
              summary.childName,
              summary.birthDate,
            ),
            summary.referredTo?.let { stringResource(Res.string.registration_referred, it) },
          )
          .joinToString(" "),
      tone = Tone.Success,
    )
    if (summary.listStale) {
      AlertCard(title = null, text = stringResource(Res.string.list_stale), tone = Tone.Warning)
    }
    if (summary.doses.isNotEmpty()) {
      SectionCard(
        title = stringResource(Res.string.registration_doses_title, summary.doses.size),
        icon = OnesaIcons.Syringe,
      ) {
        summary.doses.forEach {
          RecordRow(
            it,
            icon = OnesaIcons.Syringe,
            status = Status("primarySource: false", Tone.Warning),
          )
        }
      }
    }
  }
}

private fun writes(counts: Map<String, Int>): String =
  counts.entries.joinToString(", ") { (type, count) -> if (count > 1) "$type ×$count" else type }

private const val ONESA = "Onesa"
