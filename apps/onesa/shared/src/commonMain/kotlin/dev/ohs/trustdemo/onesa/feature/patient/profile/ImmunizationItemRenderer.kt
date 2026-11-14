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
import dev.ohs.trustdemo.onesa.generated.resources.immunization_given
import dev.ohs.trustdemo.onesa.generated.resources.immunization_unknown
import dev.ohs.trustdemo.onesa.generated.resources.immunization_verified
import dev.ohs.trustdemo.onesa.generated.state.PatientImmunizationState
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource

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
      icon = OnesaIcons.Syringe,
      detail =
        listOfNotNull(
            if (config.showDate != false)
              item.occurrenceDate?.let { stringResource(Res.string.immunization_given, it) }
            else null,
            if (item.verified == true) stringResource(Res.string.immunization_verified) else null,
          )
          .joinToString(", ")
          .ifEmpty { null },
      status =
        if (config.showStatus != false)
          item.immunizationStatus?.let {
            val tone =
              when (it.lowercase()) {
                "completed" -> Tone.Success
                "not-done" -> Tone.Danger
                else -> Tone.Neutral
              }
            Status(it.replaceFirstChar { c -> c.uppercaseChar() }, tone)
          }
        else null,
    )
  }
}
