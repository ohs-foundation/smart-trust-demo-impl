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
package dev.ohs.trustdemo.onesa.feature.patient.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.trustdemo.onesa.feature.home.rememberPlace
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.patient_list_empty
import dev.ohs.trustdemo.onesa.generated.resources.patient_list_title
import dev.ohs.trustdemo.onesa.generated.resources.patient_list_title_place
import dev.ohs.trustdemo.onesa.generated.state.PatientSummaryState
import dev.ohs.trustdemo.onesa.generated.viewtype.ViewTypeCS
import dev.ohs.trustdemo.onesa.ui.SectionCard
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The clinician's Patients tab: the patients synced to this clinic. */
@Composable
fun PatientListScreen(onPatientClick: (String) -> Unit) {
  val viewModel: PatientListViewModel = koinViewModel()
  val patients by viewModel.patients.collectAsStateWithLifecycle()
  val loaded = patients
  if (loaded == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }
  PatientListContent(loaded, rememberPlace(), onPatientClick)
}

@Composable
fun PatientListContent(
  patients: List<PatientSummaryState>,
  place: String?,
  onPatientClick: (String) -> Unit,
) {
  val registry = LocalViewRegistry.current
  val card =
    remember(registry) { registry.componentRenderer<PatientSummaryState>(ViewTypeCS.PatientCard) }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SectionCard(
      title =
        place?.let { stringResource(Res.string.patient_list_title_place, it) }
          ?: stringResource(Res.string.patient_list_title),
      summary = Status(patients.size.toString(), Tone.Info),
    ) {
      if (patients.isEmpty()) {
        Text(
          stringResource(Res.string.patient_list_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = OnesaColors.Muted,
        )
      }
      patients.forEach { patient ->
        card.Render(patient, RenderOptions(onClick = { onPatientClick(patient.patientId ?: "") }))
      }
    }
  }
}
