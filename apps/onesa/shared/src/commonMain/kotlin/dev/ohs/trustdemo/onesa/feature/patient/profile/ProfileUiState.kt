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

import dev.ohs.trustdemo.onesa.data.schedule.BookedVisit
import dev.ohs.trustdemo.onesa.generated.state.AllergyReactionState
import dev.ohs.trustdemo.onesa.generated.state.PatientAllergyState
import dev.ohs.trustdemo.onesa.generated.state.PatientConditionState
import dev.ohs.trustdemo.onesa.generated.state.PatientContactState
import dev.ohs.trustdemo.onesa.generated.state.PatientImmunizationState
import dev.ohs.trustdemo.onesa.generated.state.PatientMedicationState
import dev.ohs.trustdemo.onesa.generated.state.PatientSummaryState
import dev.ohs.trustdemo.onesa.generated.state.PatientTelecomState

data class ProfileUiState(
  val patient: PatientSummaryState? = null,
  val allergies: List<PatientAllergyState> = emptyList(),
  val allergyReactions: List<AllergyReactionState> = emptyList(),
  val medications: List<PatientMedicationState> = emptyList(),
  val conditions: List<PatientConditionState> = emptyList(),
  val immunizations: List<PatientImmunizationState> = emptyList(),
  val contacts: List<PatientContactState> = emptyList(),
  val telecoms: List<PatientTelecomState> = emptyList(),
  val visit: BookedVisit? = null,
)
