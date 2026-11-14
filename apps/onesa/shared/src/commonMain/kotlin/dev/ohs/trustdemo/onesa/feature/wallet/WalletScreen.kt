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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.repository.displayAge
import dev.ohs.trustdemo.onesa.data.repository.shortAge
import dev.ohs.trustdemo.onesa.data.sharing.FamilyDose
import dev.ohs.trustdemo.onesa.data.sharing.FamilyMember
import dev.ohs.trustdemo.onesa.data.sharing.FamilyRecords
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.verify_primary_source_false
import dev.ohs.trustdemo.onesa.generated.resources.verify_primary_source_true
import dev.ohs.trustdemo.onesa.generated.resources.wallet_card_verified
import dev.ohs.trustdemo.onesa.generated.resources.wallet_cross_border
import dev.ohs.trustdemo.onesa.generated.resources.wallet_dose_detail
import dev.ohs.trustdemo.onesa.generated.resources.wallet_doses_footnote
import dev.ohs.trustdemo.onesa.generated.resources.wallet_doses_verified
import dev.ohs.trustdemo.onesa.generated.resources.wallet_empty
import dev.ohs.trustdemo.onesa.generated.resources.wallet_household
import dev.ohs.trustdemo.onesa.generated.resources.wallet_ips
import dev.ohs.trustdemo.onesa.generated.resources.wallet_no_doses
import dev.ohs.trustdemo.onesa.generated.resources.wallet_not_synced
import dev.ohs.trustdemo.onesa.generated.resources.wallet_relation_child
import dev.ohs.trustdemo.onesa.generated.resources.wallet_relation_father
import dev.ohs.trustdemo.onesa.generated.resources.wallet_relation_head
import dev.ohs.trustdemo.onesa.generated.resources.wallet_relation_mother
import dev.ohs.trustdemo.onesa.generated.resources.wallet_selected
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** S6: the household the signed-in family member manages, and the selected member's doses. */
@Composable
fun WalletScreen() {
  val viewModel: WalletViewModel = koinViewModel()
  val family by viewModel.family.collectAsStateWithLifecycle()
  val selected by viewModel.selected.collectAsStateWithLifecycle()
  val doses by viewModel.doses.collectAsStateWithLifecycle()
  val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
  WalletContent(family, selected, doses, today, viewModel::select)
}

@Composable
fun WalletContent(
  family: List<FamilyMember>,
  selected: String?,
  doses: List<FamilyDose>,
  today: LocalDate,
  onSelect: (String) -> Unit,
) {
  val shown = family.firstOrNull { it.id == selected }
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SectionCard(title = stringResource(Res.string.wallet_household), icon = OnesaIcons.Users) {
      if (family.isEmpty()) Muted(stringResource(Res.string.wallet_empty))
      family.forEach { member ->
        RecordRow(
          label = member.given ?: member.name ?: stringResource(Res.string.wallet_not_synced),
          icon = if (member.relation == FamilyRecords.CHILD) OnesaIcons.Baby else OnesaIcons.User,
          detail = relationLine(member, today),
          status =
            if (member.id == selected) Status(stringResource(Res.string.wallet_selected), Tone.Info)
            else null,
          onClick = { onSelect(member.id) },
        )
      }
    }
    if (shown != null) {
      val cards = doses.count { it.fromCard && it.primarySource }
      SectionCard(
        title = stringResource(Res.string.wallet_ips),
        icon = OnesaIcons.ShieldCheck,
        summary =
          doses
            .count { it.primarySource }
            .takeIf { it > 0 }
            ?.let { Status(stringResource(Res.string.wallet_doses_verified, it), Tone.Success) },
        footnote =
          doses
            .takeIf { it.isNotEmpty() }
            ?.let {
              stringResource(
                Res.string.wallet_doses_footnote,
                listOfNotNull(
                    shown.name,
                    displayAge(shown.birthDate, today)?.let { age -> "($age)" },
                  )
                  .joinToString(" "),
                cards,
                doses.size - cards,
              )
            },
      ) {
        if (doses.isEmpty()) Muted(stringResource(Res.string.wallet_no_doses))
        doses.forEach { dose -> DoseRow(dose) }
      }
    }
  }
}

@Composable
private fun DoseRow(dose: FamilyDose) {
  if (dose.fromCard && dose.primarySource) {
    RecordRow(
      label = dose.name,
      icon = OnesaIcons.Globe,
      code = listOfNotNull(dose.lot, dose.scheduleCode).joinToString(" • ").ifEmpty { null },
      detail =
        dose.origin?.let {
          stringResource(Res.string.wallet_cross_border, NewbornRegistration.countryName(it))
        },
      status = Status(stringResource(Res.string.wallet_card_verified), Tone.Success),
    )
  } else {
    RecordRow(
      label = dose.name,
      icon = OnesaIcons.Syringe,
      detail = stringResource(Res.string.wallet_dose_detail, dose.date ?: "-", dose.lot ?: "-"),
      status =
        if (dose.primarySource)
          Status(stringResource(Res.string.verify_primary_source_true), Tone.Success)
        else Status(stringResource(Res.string.verify_primary_source_false), Tone.Warning),
    )
  }
}

/** A member's place in the household: `Child (12m)`, `Father (Head)`, `Mother`. */
@Composable
internal fun relationLine(member: FamilyMember, today: LocalDate): String? {
  val relation =
    when (member.relation) {
      FamilyRecords.CHILD -> stringResource(Res.string.wallet_relation_child)
      "FTH" -> stringResource(Res.string.wallet_relation_father)
      "MTH" -> stringResource(Res.string.wallet_relation_mother)
      else -> null
    }
  val note =
    if (member.head) stringResource(Res.string.wallet_relation_head)
    else if (member.relation == FamilyRecords.CHILD) shortAge(member.birthDate, today) else null
  return listOfNotNull(relation, note?.let { "($it)" }).joinToString(" ").ifEmpty { null }
}

@Composable
private fun Muted(text: String) {
  Text(text, style = MaterialTheme.typography.bodyMedium, color = OnesaColors.Muted)
}
