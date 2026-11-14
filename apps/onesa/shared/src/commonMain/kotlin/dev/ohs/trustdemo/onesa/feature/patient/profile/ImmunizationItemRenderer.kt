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

import androidx.compose.runtime.Composable
import dev.ohs.player.client.renderer.ComponentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.trustdemo.onesa.generated.config.ImmunizationItemConfig
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.immunization_codes
import dev.ohs.trustdemo.onesa.generated.resources.immunization_lot
import dev.ohs.trustdemo.onesa.generated.resources.immunization_unknown
import dev.ohs.trustdemo.onesa.generated.resources.verify_primary_source_false
import dev.ohs.trustdemo.onesa.generated.resources.verify_primary_source_true
import dev.ohs.trustdemo.onesa.generated.state.PatientImmunizationState
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource

/**
 * A dose as the patient profile lists it: the vaccine, its product and national schedule codes,
 * when, which lot and where, and whether it is the clinic's own record (`primarySource`).
 */
class ImmunizationItemRenderer :
  ComponentRenderer<PatientImmunizationState, ImmunizationItemConfig> {
  @Composable
  override fun Render(
    item: PatientImmunizationState,
    config: ImmunizationItemConfig,
    options: RenderOptions,
  ) {
    RecordRow(
      label = item.vaccineName ?: stringResource(Res.string.immunization_unknown),
      modifier = options.modifier,
      code =
        if (item.productCode != null || item.scheduleCode != null)
          stringResource(
            Res.string.immunization_codes,
            item.productCode ?: "-",
            item.scheduleCode ?: "-",
          )
        else null,
      detail =
        listOfNotNull(
            if (config.showDate != false) item.occurrenceDate?.toString()?.take(10) else null,
            item.lotNumber?.let { stringResource(Res.string.immunization_lot, it) },
            item.location,
          )
          .joinToString(" • ")
          .ifEmpty { null },
      status =
        if (config.showStatus != false)
          item.verified?.let {
            if (it) Status(stringResource(Res.string.verify_primary_source_true), Tone.Success)
            else Status(stringResource(Res.string.verify_primary_source_false), Tone.Warning)
          }
        else null,
    )
  }
}
