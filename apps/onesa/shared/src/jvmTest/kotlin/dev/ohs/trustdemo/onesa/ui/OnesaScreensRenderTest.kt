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
package dev.ohs.trustdemo.onesa.ui

import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.feature.home.HomeShell
import dev.ohs.trustdemo.onesa.feature.home.ShellHeader
import dev.ohs.trustdemo.onesa.feature.login.LoginScreen
import dev.ohs.trustdemo.onesa.feature.role.NoRoleScreen
import dev.ohs.trustdemo.onesa.feature.sync.InitialSyncGateState
import dev.ohs.trustdemo.onesa.feature.sync.InitialSyncScreen
import dev.ohs.trustdemo.onesa.feature.sync.PendingUpload
import dev.ohs.trustdemo.onesa.feature.sync.StepState
import dev.ohs.trustdemo.onesa.feature.sync.SyncPart
import dev.ohs.trustdemo.onesa.feature.sync.SyncStatusContent
import dev.ohs.trustdemo.onesa.feature.sync.SyncStep
import dev.ohs.trustdemo.onesa.feature.sync.SyncStepsState
import dev.ohs.trustdemo.onesa.ui.ScreenshotHarness.Phone
import dev.ohs.trustdemo.onesa.ui.ScreenshotHarness.Tablet
import dev.ohs.trustdemo.onesa.ui.ScreenshotHarness.render
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import kotlin.test.Test
import kotlin.time.Instant

/** Renders the kit and the shared screens with the mockups' story data, for side-by-side review. */
class OnesaScreensRenderTest {
  private val chwHeader =
    ShellHeader(
      title = "Onesa Community Health",
      icon = OnesaIcons.Users,
      subtitle = "Catchment Village 04 • CHW: Vilay",
      status = Status("Offline (28d)", Tone.Warning),
    )

  @Test
  fun kitSheet() {
    render("kit-sheet", 420.dp to 2960.dp) { KitSheet() }
  }

  @Test
  fun signIn() {
    render("1-01-sign-in") {
      LoginScreen(signingIn = false, error = null, onSignIn = {}, onErrorDismiss = {})
    }
  }

  @Test
  fun noRole() {
    render("1-02-no-role") { NoRoleScreen(message = null, onRetry = {}, onSignOut = {}) }
  }

  @Test
  fun firstSync() {
    val steps =
      listOf(
        SyncStep(
          SyncPart.Organization,
          StepState.Done,
          2,
          listOf("Lomaru District Health Center", "Village 04"),
        ),
        SyncStep(SyncPart.Households, StepState.Done, 1, listOf("Sengmany household")),
        SyncStep(SyncPart.People, StepState.Done, 2, listOf("Bounmy Sengmany", "Khamla Sengmany")),
        SyncStep(SyncPart.Records, StepState.Done),
        SyncStep(
          SyncPart.Practitioner,
          StepState.Downloading,
          1,
          listOf("Practitioner/TrustDemoVilay"),
        ),
      )
    render("1-03-first-sync") {
      InitialSyncScreen(
        state = InitialSyncGateState.Syncing,
        onRetry = {},
        onContinueAnyway = {},
        progress = SyncStepsState(steps, place = "Village 04"),
        userName = "Vilay",
        role = AppRole.CHW,
      )
    }
  }

  @Test
  fun syncStatus() {
    val pending =
      listOf(
        PendingUpload("Somsak Sengmany", "Patient/TrustDemoSomsak"),
        PendingUpload("Sengmany household", "Group/TrustDemoHousehold"),
        PendingUpload("Khamla Sengmany", "RelatedPerson/TrustDemoKhamla"),
        PendingUpload("Bounmy Sengmany", "RelatedPerson/TrustDemoBounmy"),
        PendingUpload("Immunization", "Immunization/TrustDemoReportedHepB"),
        PendingUpload("Immunization", "Immunization/TrustDemoReportedBcg"),
        PendingUpload("ServiceRequest", "ServiceRequest/TrustDemoReferral"),
      )
    render("1-04-sync-status", 360.dp to 960.dp) {
      HomeShell(
        header = chwHeader,
        tabs = listOf("Households", "Trust list", "Sync"),
        selected = 2,
        onSelect = {},
        menu = {},
        actions = listOf(Action("Sync Now", {})),
      ) {
        SyncStatusContent(
          pending,
          Instant.parse("2025-10-21T03:00:00Z"),
          AppRole.CHW,
          now = Instant.parse("2025-11-18T03:00:00Z"),
        )
      }
    }
  }

  @Test
  fun shellPerRole() {
    render("shell-chw", Phone) {
      HomeShell(
        header = chwHeader,
        tabs = listOf("Households", "Trust list", "Sync"),
        selected = 0,
        onSelect = {},
        menu = {},
        actions = listOf(Action("Register Household", {}, primary = false)),
      ) {
        SectionCard(
          title = "Village 04 households",
          summary = Status("1", Tone.Info),
          footnote = "FHIR Group • profile TrustDemoOnesaHousehold",
        ) {
          RecordRow(
            "Sengmany household",
            code = "#HH-XXU-204",
            detail = "Head: Bounmy Sengmany • 2 members",
          )
        }
      }
    }
    render("shell-clinician", Tablet, OnesaColors.BrandClinic) {
      HomeShell(
        header =
          ShellHeader(
            title = "Lomaru District Health Center — OHS Player Facility EMR",
            icon = OnesaIcons.Building,
            subtitle = "Practitioner: Nurse Chanthason",
            chips = listOf(Status("Trust list cached • 2026-11-14 09:12", Tone.Success)),
          ),
        tabs = listOf("Referrals", "Households", "Trust list", "Sync"),
        selected = 0,
        onSelect = {},
        menu = {},
        actions = listOf(Action("Open Referral", {})),
      ) {
        SectionCard(
          title = "Incoming referrals to Lomaru DHC",
          summary = Status("1 open", Tone.Warning),
        ) {
          RecordRow(
            "Somsak Sengmany",
            code = "ServiceRequest/TrustDemoReferral • SNOMED 3457005",
            detail = "From CHW Vilay, Village 04 • 18 Nov 2025",
            status = Status("Routine"),
          )
        }
      }
    }
    render("shell-family", Phone, OnesaColors.BrandFamily) {
      HomeShell(
        header =
          ShellHeader(
            title = "Onesa Family Health Wallet",
            icon = OnesaIcons.Users,
            subtitle = "Sengmany family • 3 members",
            status = Status("National Portal Connected", Tone.Success),
          ),
        tabs = listOf("Family wallet"),
        selected = 0,
        onSelect = {},
        menu = {},
        actions =
          listOf(
            Action("Request Verifiable Health Links (VHL) for All 3", {}, icon = OnesaIcons.Qr)
          ),
      ) {
        SectionCard(title = "Household", icon = OnesaIcons.Users) {
          RecordRow(
            "Somsak",
            icon = OnesaIcons.Baby,
            detail = "Child (12m)",
            status = Status("Selected", Tone.Info),
          )
          RecordRow("Bounmy", icon = OnesaIcons.User, detail = "Father (Head)")
          RecordRow("Khamla", icon = OnesaIcons.User, detail = "Mother")
        }
      }
    }
  }
}
