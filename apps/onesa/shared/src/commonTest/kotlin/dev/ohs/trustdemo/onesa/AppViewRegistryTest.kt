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
package dev.ohs.trustdemo.onesa

import dev.ohs.player.client.layout.GridListRenderer
import dev.ohs.player.client.layout.HorizontalListRenderer
import dev.ohs.player.client.layout.VerticalListRenderer
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.registry.layoutRenderer
import dev.ohs.trustdemo.onesa.feature.trustlist.TrustListState
import dev.ohs.trustdemo.onesa.generated.state.GroupListState
import dev.ohs.trustdemo.onesa.generated.state.GroupMemberState
import dev.ohs.trustdemo.onesa.generated.state.PatientAllergyState
import dev.ohs.trustdemo.onesa.generated.state.PatientConditionState
import dev.ohs.trustdemo.onesa.generated.state.PatientImmunizationState
import dev.ohs.trustdemo.onesa.generated.state.PatientMedicationState
import dev.ohs.trustdemo.onesa.generated.state.PatientSummaryState
import dev.ohs.trustdemo.onesa.generated.viewtype.ViewTypeCS
import kotlin.test.Test

class AppViewRegistryTest {

  @Test
  fun allRequiredRenderersAreRegistered() {
    val registry = buildAppViewRegistry()

    // Group list
    registry.componentRenderer<GroupListState>(ViewTypeCS.GroupCard)
    registry.layoutRenderer<GroupListState>(VerticalListRenderer.VIEW_TYPE)

    // Group profile
    registry.componentRenderer<GroupMemberState>(ViewTypeCS.MemberItem)

    // Patient list — component + every layout
    registry.componentRenderer<PatientSummaryState>(ViewTypeCS.PatientCard)
    registry.layoutRenderer<PatientSummaryState>(VerticalListRenderer.VIEW_TYPE)
    registry.layoutRenderer<PatientSummaryState>(HorizontalListRenderer.VIEW_TYPE)
    registry.layoutRenderer<PatientSummaryState>(GridListRenderer.VIEW_TYPE)

    // Patient IPS profile — all IG-authored item types
    registry.componentRenderer<PatientSummaryState>(ViewTypeCS.PatientHeader)
    registry.componentRenderer<PatientAllergyState>(ViewTypeCS.AllergyItem)
    registry.componentRenderer<PatientMedicationState>(ViewTypeCS.MedicationItem)
    registry.componentRenderer<PatientConditionState>(ViewTypeCS.ConditionItem)
    registry.componentRenderer<PatientImmunizationState>(ViewTypeCS.ImmunizationItem)

    // Trust list status
    registry.componentRenderer<TrustListState>(ViewTypeCS.TrustListCard)
    registry.layoutRenderer<TrustListState>(VerticalListRenderer.VIEW_TYPE)
  }
}
