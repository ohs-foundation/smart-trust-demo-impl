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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.repository.displayAge
import dev.ohs.trustdemo.onesa.data.repository.shortAge
import dev.ohs.trustdemo.onesa.data.sharing.FamilyDose
import dev.ohs.trustdemo.onesa.data.sharing.FamilyHealth
import dev.ohs.trustdemo.onesa.data.sharing.FamilyMember
import dev.ohs.trustdemo.onesa.data.sharing.FamilyRecords
import dev.ohs.trustdemo.onesa.data.sharing.HealthItem
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.verify_primary_source_false
import dev.ohs.trustdemo.onesa.generated.resources.verify_primary_source_true
import dev.ohs.trustdemo.onesa.generated.resources.wallet_allergies
import dev.ohs.trustdemo.onesa.generated.resources.wallet_card_verified
import dev.ohs.trustdemo.onesa.generated.resources.wallet_cross_border
import dev.ohs.trustdemo.onesa.generated.resources.wallet_dose_detail
import dev.ohs.trustdemo.onesa.generated.resources.wallet_doses_footnote
import dev.ohs.trustdemo.onesa.generated.resources.wallet_doses_verified
import dev.ohs.trustdemo.onesa.generated.resources.wallet_empty
import dev.ohs.trustdemo.onesa.generated.resources.wallet_household
import dev.ohs.trustdemo.onesa.generated.resources.wallet_ips
import dev.ohs.trustdemo.onesa.generated.resources.wallet_medications
import dev.ohs.trustdemo.onesa.generated.resources.wallet_no_doses
import dev.ohs.trustdemo.onesa.generated.resources.wallet_not_synced
import dev.ohs.trustdemo.onesa.generated.resources.wallet_problems
import dev.ohs.trustdemo.onesa.generated.resources.wallet_relation_child
import dev.ohs.trustdemo.onesa.generated.resources.wallet_relation_father
import dev.ohs.trustdemo.onesa.generated.resources.wallet_relation_head
import dev.ohs.trustdemo.onesa.generated.resources.wallet_relation_mother
import dev.ohs.trustdemo.onesa.generated.resources.wallet_viewing
import dev.ohs.trustdemo.onesa.generated.resources.wallet_vital_signs
import dev.ohs.trustdemo.onesa.ui.CardTitle
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.StatusChip
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * S6: the household the signed-in family member manages, each open member's summary under them:
 * their problems, medicines, allergies and vital signs, then their doses.
 */
@Composable
fun WalletScreen() {
  val viewModel: WalletViewModel = koinViewModel()
  val family by viewModel.family.collectAsStateWithLifecycle()
  val open by viewModel.open.collectAsStateWithLifecycle()
  val doses by viewModel.doses.collectAsStateWithLifecycle()
  val health by viewModel.health.collectAsStateWithLifecycle()
  val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
  WalletContent(family, open, doses, today, viewModel::toggle, health)
}

@Composable
fun WalletContent(
  family: List<FamilyMember>,
  open: Set<String>,
  doses: Map<String, List<FamilyDose>>,
  today: LocalDate,
  onToggle: (String) -> Unit,
  health: Map<String, FamilyHealth> = emptyMap(),
) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SectionCard(title = stringResource(Res.string.wallet_household), icon = OnesaIcons.Users) {
      if (family.isEmpty()) Muted(stringResource(Res.string.wallet_empty))
      family.forEach { member ->
        val viewing = member.id in open
        RecordRow(
          label = member.given ?: member.name ?: stringResource(Res.string.wallet_not_synced),
          icon = if (member.relation == FamilyRecords.CHILD) OnesaIcons.Baby else OnesaIcons.User,
          detail = relationLine(member, today),
          status =
            if (viewing) Status(stringResource(Res.string.wallet_viewing), Tone.Info) else null,
          selected = viewing,
          onClick = { onToggle(member.id) },
        )
        if (viewing) {
          doses[member.id]?.let {
            MemberSummary(member, it, health[member.id] ?: FamilyHealth(), today)
          }
        }
      }
    }
  }
}

/** A member's summary, threaded under their row in the brand. */
@Composable
private fun MemberSummary(
  member: FamilyMember,
  doses: List<FamilyDose>,
  health: FamilyHealth,
  today: LocalDate,
) {
  val brand = MaterialTheme.colorScheme.primary
  val cards = doses.count { it.fromCard && it.primarySource }
  val verified = doses.count { it.primarySource }
  Column(
    Modifier.fillMaxWidth()
      .drawBehind { drawRect(brand, size = Size(2.dp.toPx(), size.height)) }
      .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    HealthSection(Res.string.wallet_problems, OnesaIcons.Layers, health.problems)
    HealthSection(Res.string.wallet_medications, OnesaIcons.File, health.medications)
    HealthSection(Res.string.wallet_allergies, OnesaIcons.Alert, health.allergies)
    HealthSection(Res.string.wallet_vital_signs, OnesaIcons.Clock, health.vitalSigns)
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalArrangement = Arrangement.spacedBy(6.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      CardTitle(stringResource(Res.string.wallet_ips), OnesaIcons.ShieldCheck)
      if (verified > 0) {
        StatusChip(Status(stringResource(Res.string.wallet_doses_verified, verified), Tone.Success))
      }
    }
    if (doses.isEmpty()) Muted(stringResource(Res.string.wallet_no_doses))
    doses.forEach { dose -> DoseRow(dose) }
    if (doses.isNotEmpty()) {
      Text(
        stringResource(
          Res.string.wallet_doses_footnote,
          listOfNotNull(member.name, displayAge(member.birthDate, today)?.let { "($it)" })
            .joinToString(" "),
          cards,
          doses.size - cards,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = OnesaColors.Slate500,
      )
    }
  }
}

@Composable
private fun HealthSection(title: StringResource, icon: ImageVector, items: List<HealthItem>) {
  if (items.isEmpty()) return
  CardTitle(stringResource(title), icon)
  items.forEach { RecordRow(label = it.label, detail = it.detail) }
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
