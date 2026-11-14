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
import dev.ohs.trustdemo.onesa.generated.config.AllergyItemConfig
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.allergy_unknown
import dev.ohs.trustdemo.onesa.generated.state.PatientAllergyState
import dev.ohs.trustdemo.onesa.ui.RecordRow
import dev.ohs.trustdemo.onesa.ui.Status
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import org.jetbrains.compose.resources.stringResource

class AllergyItemRenderer : ComponentRenderer<PatientAllergyState, AllergyItemConfig> {
  @Composable
  override fun Render(
    item: PatientAllergyState,
    config: AllergyItemConfig,
    options: RenderOptions,
  ) {
    RecordRow(
      label = item.substance ?: stringResource(Res.string.allergy_unknown),
      modifier = options.modifier,
      detail =
        if (config.showCriticality != false) {
          item.criticality?.replaceFirstChar { it.uppercaseChar() }
        } else null,
      status =
        if (config.showStatus != false)
          item.allergyStatus?.let {
            Status(
              it.replaceFirstChar { c -> c.uppercaseChar() },
              criticalityTone(item.criticality),
            )
          }
        else null,
    )
  }
}

fun criticalityTone(criticality: String?): Tone =
  when (criticality?.lowercase()) {
    "high" -> Tone.Danger
    "moderate" -> Tone.Warning
    "low" -> Tone.Success
    else -> Tone.Neutral
  }
