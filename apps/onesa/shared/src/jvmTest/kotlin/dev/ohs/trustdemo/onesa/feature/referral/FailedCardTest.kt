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
package dev.ohs.trustdemo.onesa.feature.referral

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.ohs.trustdemo.hcert.FailureReason
import dev.ohs.trustdemo.hcert.VerifyStep
import dev.ohs.trustdemo.onesa.data.verification.ScanOutcome
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import kotlin.test.Test
import kotlinx.datetime.LocalDate

/**
 * S4 says why a card failed, by the step and reason the engine reports, and offers only Scan Again:
 * the doses stay reported.
 */
@OptIn(ExperimentalTestApi::class)
class FailedCardTest {
  private fun shows(
    step: VerifyStep,
    reason: FailureReason,
    title: String,
    resolution: String,
    code: String,
    status: String,
  ) = runComposeUiTest {
    setContent {
      OnesaTheme(OnesaColors.BrandClinic) {
        VerifyCardsContent(
          state =
            VerifyCardsUiState(
              scanned = "HC1:6BFOXN",
              results = listOf(ScanOutcome.Failed(step, reason, "916ffa567c0f44a2")),
            ),
          today = LocalDate.parse("2025-11-25"),
          topBar = {},
          showOutcome = true,
          actions = VerifyActions({}, {}, {}, {}, {}, {}, {}),
        )
      }
    }
    onNodeWithText(title).assertExists()
    onNodeWithText("FAIL").assertExists()
    onNodeWithText(resolution).assertExists()
    onNodeWithText(code).assertExists()
    onNodeWithText("The record stays primarySource: false; nothing is upgraded.", substring = true)
      .assertExists()
    onNodeWithText("Scan Again").assertIsEnabled()
    onNodeWithText("Confirm Verification & Proceed to Schedule Evaluation")
      .assertIsNotEnabled()
      .assert(
        SemanticsMatcher.expectValue(
          SemanticsProperties.StateDescription,
          "Unavailable: $status, so the doses stay as reported",
        )
      )
    onNodeWithText("Keep Doses as Reported").assertDoesNotExist()
  }

  @Test
  fun aTamperedSignatureIsAnAlteredCard() {
    shows(
      VerifyStep.SIGNATURE,
      FailureReason.SIGNATURE_INVALID,
      "SIGNATURE VERIFICATION FAILED — INVALID COSE_Sign1",
      "Untrusted / Altered Card",
      "ERR_CRYPTO_SIG_MISMATCH",
      "Signature rejected",
    )
  }

  @Test
  fun aKeyOffTheTrustListOrACscaKeyIsAnUnknownSigner() {
    shows(
      VerifyStep.TRUST_LOOKUP,
      FailureReason.KID_NOT_FOUND,
      "SIGNER NOT ON THE TRUST LIST — UNKNOWN KEY ID",
      "Untrusted / Unknown Signer",
      "ERR_KID_NOT_FOUND",
      "Signer rejected",
    )
  }

  @Test
  fun anExpiredCardIsExpired() {
    shows(
      VerifyStep.TIME,
      FailureReason.EXPIRED,
      "CERTIFICATE EXPIRED — OUTSIDE ITS VALIDITY",
      "Untrusted / Expired Card",
      "ERR_CERT_EXPIRED",
      "Certificate expired",
    )
  }

  @Test
  fun aCardIssuedLaterThanNowIsNotYetValid() {
    shows(
      VerifyStep.TIME,
      FailureReason.NOT_YET_VALID,
      "CERTIFICATE NOT YET VALID — OUTSIDE ITS VALIDITY",
      "Untrusted / Not Yet Valid",
      "ERR_CERT_NOT_YET_VALID",
      "Certificate not yet valid",
    )
  }

  @Test
  fun aKeyNotForVaccinationCardsIsTheWrongKeyUsage() {
    shows(
      VerifyStep.KEY_USAGE,
      FailureReason.KEY_USAGE_MISMATCH,
      "SIGNER NOT AUTHORISED — WRONG KEY USAGE",
      "Untrusted / Signer Not Authorised",
      "ERR_KEY_USAGE",
      "Signer rejected",
    )
  }

  @Test
  fun anAlgorithmTheFrameworkRefusesIsABadHeader() {
    shows(
      VerifyStep.HEADER,
      FailureReason.UNSUPPORTED_ALGORITHM,
      "INVALID COSE_Sign1 HEADER — SIGNATURE NOT CHECKED",
      "Unreadable / Bad Header",
      "ERR_COSE_HEADER",
      "Header rejected",
    )
  }

  @Test
  fun textThatIsNotAnHc1CardIsNotDecoded() {
    shows(
      VerifyStep.BASE45,
      FailureReason.BASE45_INVALID,
      "CARD NOT DECODED — NOT A VALID HC1 CERTIFICATE",
      "Unreadable / Not a Vaccination Certificate",
      "ERR_DECODE",
      "Card unreadable",
    )
  }
}
