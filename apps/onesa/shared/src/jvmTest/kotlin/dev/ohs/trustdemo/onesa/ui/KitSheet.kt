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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone

private const val HC1 =
  "HC1:6BFOXN%TSMAHN-H8/N2S\$K980F2A.0K.136D/S6A5\$P*Q0*3YF3\$G4*43-C3XED9/D:98\$H:836XDXK7/93.3%J9"

/** Every kit component with story data from the mockups, top to bottom. */
@Composable
fun KitSheet() {
  Column(
    Modifier.fillMaxSize().background(OnesaColors.Bg),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    OnesaTopBar(
      title = "Onesa Community Health",
      icon = OnesaIcons.Users,
      subtitle = "Catchment Village 04 • CHW: Vilay",
      status = Status("Offline (28d)", Tone.Warning),
    )
    OnesaTopBar(
      title = "Lomaru District Health Center — OHS Player Facility EMR",
      icon = OnesaIcons.Building,
      subtitle = "Practitioner: Nurse Chanthason",
      chips = listOf(Status("DEV Trust List Cached • Updated 2h ago", Tone.Success)),
    )
    PatientBanner(
      name = "Somsak Sengmany",
      avatar = OnesaIcons.Baby,
      avatarColor = ChildAvatar,
      chips =
        listOf(
          Status("Age: 4 Weeks (T0+4w)", Tone.Info),
          Status("Referred by CHW Vilay", Tone.Warning),
        ),
    )
    Column(
      Modifier.padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Label("StatusChip")
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Tone.entries.forEach { StatusChip(Status(it.name, it)) }
        StatusChip(Status("Verified", Tone.Success, OnesaIcons.Check))
      }
      Label("SectionCard, RecordRow")
      SectionCard(
        title = "Unverified Records Under Review",
        icon = OnesaIcons.Qr,
        summary = Status("2 Pending DVC Doses", Tone.Warning),
        footnote = "#HH-XXU-204 • FHIR Group",
      ) {
        RecordRow(
          "Hepatitis B Pediatric Dose",
          icon = OnesaIcons.Syringe,
          code = "Code: a3K3X000006MiiXUAS • Lot: XXR-4821",
          detail = "Origin: Kesaro Provincial Hospital (Taifafeki)",
          status = Status("primarySource: false", Tone.Warning),
        )
        RecordRow("Bounmy Sengmany", icon = OnesaIcons.User, detail = "Father (29y) • Head")
      }
      Label("AlertCard, NoteLine")
      AlertCard(
        "Unverified Foreign Record (read, not checked)",
        "Payload decoded offline from paper QR. Nothing is saved yet.",
        Tone.Warning,
      )
      AlertCard(
        "Uploads start when there is signal.",
        "The national server accepts households in your catchment only.",
        Tone.Info,
      )
      NoteLine("Works offline. Nothing is checked or saved yet.")
      Label("FieldGrid")
      SectionCard(title = "Incoming CHW Referral", icon = OnesaIcons.File) {
        FieldGrid(
          listOf(
            Field("ServiceRequest", "#req-verify-001", mono = true),
            Field("Reason", "Foreign vaccination cards presented at household in Village 04."),
          )
        )
      }
      Label("VerificationResultCard")
      VerificationResultCard(
        "VALID COSE_Sign1 SIGNATURE (ES256)",
        Tone.Success,
        verdict = "PASS",
        fields =
          listOf(
            Field("Trust Authority", "Taifafeki MOPH (DEV Participant XXR)"),
            Field("Key ID (kid)", "916ffa567c0f44a2", mono = true),
          ),
        detail = "Header Validated • Certificate Within DSC Validity Window.",
      )
      VerificationResultCard(
        "SIGNATURE CHECK FAILED",
        Tone.Danger,
        verdict = "FAIL",
        fields = listOf(Field("Failed at step", "SIGNATURE", mono = true)),
      )
      Label("CameraPanel")
      CameraPanel(
        "Point the camera at the QR on the card",
        caption = "Card 1 of 2 • Khamla holds the card",
        openCameraLabel = "Scan with camera",
        camera = {},
      )
      CameraPanel(
        "Optical Camera Feed • Live Frame",
        badge = Status("Decoded: HC1:6BFOXN...", Tone.Success),
        frame = HC1,
      )
      Label("QrPanel")
      QrPanel(
        payload = "https://vhl.onesa.example/vhl/manifest/somsak#key=f91b7c3d2e5a8812",
        title = "IHE ITI Verifiable Health Link (VHL)",
        label = "Somsak Sengmany",
        badge =
          Status(
            "Signed VHL Manifest • Onesan National Portal",
            Tone.Success,
            OnesaIcons.ShieldCheck,
          ),
        caption = "QR contains secure token link & key only. Patient data stays in Onesa.",
      )
      Label("PasscodeCard")
      var shown by remember { mutableStateOf(false) }
      PasscodeCard(
        title = "Passcode Protection — Father's Verbal Passcode",
        value = "4829",
        shown = shown,
        onShownChange = { shown = it },
        shownLabel = "Shown",
        hiddenLabel = "Hidden",
        note = "ACTIVE • Reveal to read it out to the clinician",
      )
      Label("PillTabs")
      PillTabs(listOf("Households", "Referrals", "Sync"), selected = 0, onSelect = {})
      Label("Form fields")
      SectionCard {
        OnesaTextField("No camera? Paste the card text", "", {}, placeholder = "HC1:…")
        OnesaSelectField(
          "Purpose",
          "Treatment abroad (TREAT)",
          listOf("Treatment abroad (TREAT)"),
          {},
        )
        FormLabel("Who is travelling")
        OptionRow("Somsak", selected = true, onClick = {}, check = true, code = "Child (12m)")
        OptionRow("Khamla", selected = false, onClick = {}, check = true, code = "Mother")
        OptionRow(
          "Hep B birth dose (monovalent)",
          selected = true,
          onClick = {},
          code = "XXU-EPI-HEPB-BD",
        )
        OptionRow("Pentavalent 1", selected = false, onClick = {}, code = "XXU-EPI-PENTA-1")
      }
    }
    PrimaryActionBar(
      listOf(
        Action("Scan Foreign Vaccination Card", {}),
        Action("Register Newborn", {}, primary = false),
      ),
      caption = "Born abroad? Start with the paper cards.",
    )
  }
}

@Composable
private fun Label(text: String) {
  Text(text, style = MaterialTheme.typography.labelSmall, color = OnesaColors.Slate500)
}
