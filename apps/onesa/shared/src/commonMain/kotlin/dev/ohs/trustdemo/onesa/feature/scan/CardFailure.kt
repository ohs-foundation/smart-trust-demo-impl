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
package dev.ohs.trustdemo.onesa.feature.scan

import dev.ohs.trustdemo.hcert.FailureReason
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.fail_decode_badge
import dev.ohs.trustdemo.onesa.generated.resources.fail_decode_detail
import dev.ohs.trustdemo.onesa.generated.resources.fail_decode_resolution
import dev.ohs.trustdemo.onesa.generated.resources.fail_decode_status
import dev.ohs.trustdemo.onesa.generated.resources.fail_decode_title
import dev.ohs.trustdemo.onesa.generated.resources.fail_expired_badge
import dev.ohs.trustdemo.onesa.generated.resources.fail_expired_detail
import dev.ohs.trustdemo.onesa.generated.resources.fail_expired_resolution
import dev.ohs.trustdemo.onesa.generated.resources.fail_expired_status
import dev.ohs.trustdemo.onesa.generated.resources.fail_expired_title
import dev.ohs.trustdemo.onesa.generated.resources.fail_header_badge
import dev.ohs.trustdemo.onesa.generated.resources.fail_header_detail
import dev.ohs.trustdemo.onesa.generated.resources.fail_header_resolution
import dev.ohs.trustdemo.onesa.generated.resources.fail_header_status
import dev.ohs.trustdemo.onesa.generated.resources.fail_header_title
import dev.ohs.trustdemo.onesa.generated.resources.fail_key_usage_badge
import dev.ohs.trustdemo.onesa.generated.resources.fail_key_usage_detail
import dev.ohs.trustdemo.onesa.generated.resources.fail_key_usage_resolution
import dev.ohs.trustdemo.onesa.generated.resources.fail_key_usage_status
import dev.ohs.trustdemo.onesa.generated.resources.fail_key_usage_title
import dev.ohs.trustdemo.onesa.generated.resources.fail_not_yet_valid_badge
import dev.ohs.trustdemo.onesa.generated.resources.fail_not_yet_valid_detail
import dev.ohs.trustdemo.onesa.generated.resources.fail_not_yet_valid_resolution
import dev.ohs.trustdemo.onesa.generated.resources.fail_not_yet_valid_status
import dev.ohs.trustdemo.onesa.generated.resources.fail_not_yet_valid_title
import dev.ohs.trustdemo.onesa.generated.resources.fail_signature_badge
import dev.ohs.trustdemo.onesa.generated.resources.fail_signature_detail
import dev.ohs.trustdemo.onesa.generated.resources.fail_signature_resolution
import dev.ohs.trustdemo.onesa.generated.resources.fail_signature_status
import dev.ohs.trustdemo.onesa.generated.resources.fail_signature_title
import dev.ohs.trustdemo.onesa.generated.resources.fail_signer_badge
import dev.ohs.trustdemo.onesa.generated.resources.fail_signer_detail
import dev.ohs.trustdemo.onesa.generated.resources.fail_signer_resolution
import dev.ohs.trustdemo.onesa.generated.resources.fail_signer_status
import dev.ohs.trustdemo.onesa.generated.resources.fail_signer_title
import org.jetbrains.compose.resources.StringResource

/**
 * Why a card failed verification, as S4 shows it: the [title], what that makes of the card
 * ([resolution]), a diagnostic [code] named after the engine's failed step, what it means
 * ([detail]), the camera's [badge] and the action bar's [status].
 */
enum class CardFailure(
  val code: String,
  val title: StringResource,
  val resolution: StringResource,
  val detail: StringResource,
  val badge: StringResource,
  val status: StringResource,
) {
  SignatureMismatch(
    "ERR_CRYPTO_SIG_MISMATCH",
    Res.string.fail_signature_title,
    Res.string.fail_signature_resolution,
    Res.string.fail_signature_detail,
    Res.string.fail_signature_badge,
    Res.string.fail_signature_status,
  ),
  UnknownSigner(
    "ERR_KID_NOT_FOUND",
    Res.string.fail_signer_title,
    Res.string.fail_signer_resolution,
    Res.string.fail_signer_detail,
    Res.string.fail_signer_badge,
    Res.string.fail_signer_status,
  ),
  Expired(
    "ERR_CERT_EXPIRED",
    Res.string.fail_expired_title,
    Res.string.fail_expired_resolution,
    Res.string.fail_expired_detail,
    Res.string.fail_expired_badge,
    Res.string.fail_expired_status,
  ),
  NotYetValid(
    "ERR_CERT_NOT_YET_VALID",
    Res.string.fail_not_yet_valid_title,
    Res.string.fail_not_yet_valid_resolution,
    Res.string.fail_not_yet_valid_detail,
    Res.string.fail_not_yet_valid_badge,
    Res.string.fail_not_yet_valid_status,
  ),
  WrongKeyUsage(
    "ERR_KEY_USAGE",
    Res.string.fail_key_usage_title,
    Res.string.fail_key_usage_resolution,
    Res.string.fail_key_usage_detail,
    Res.string.fail_key_usage_badge,
    Res.string.fail_key_usage_status,
  ),
  BadHeader(
    "ERR_COSE_HEADER",
    Res.string.fail_header_title,
    Res.string.fail_header_resolution,
    Res.string.fail_header_detail,
    Res.string.fail_header_badge,
    Res.string.fail_header_status,
  ),
  Undecodable(
    "ERR_DECODE",
    Res.string.fail_decode_title,
    Res.string.fail_decode_resolution,
    Res.string.fail_decode_detail,
    Res.string.fail_decode_badge,
    Res.string.fail_decode_status,
  );

  companion object {
    /**
     * The failure the engine's [reason] names. A CSCA key is dropped from the trust store, so a
     * card it signed fails as an unknown key.
     */
    fun of(reason: FailureReason): CardFailure =
      when (reason) {
        FailureReason.SIGNATURE_INVALID -> SignatureMismatch
        FailureReason.KID_NOT_FOUND -> UnknownSigner
        FailureReason.EXPIRED -> Expired
        FailureReason.NOT_YET_VALID -> NotYetValid
        FailureReason.KEY_USAGE_MISMATCH -> WrongKeyUsage
        FailureReason.HEADER_INVALID,
        FailureReason.UNSUPPORTED_ALGORITHM -> BadHeader
        FailureReason.BAD_PREFIX,
        FailureReason.UNSUPPORTED_CONTEXT_VERSION,
        FailureReason.BASE45_INVALID,
        FailureReason.INFLATE_FAILED,
        FailureReason.LIMIT_EXCEEDED,
        FailureReason.COSE_MALFORMED,
        FailureReason.HCERT_MISSING,
        FailureReason.MULTIPLE_SUBCLAIMS,
        FailureReason.PAYLOAD_INVALID -> Undecodable
      }
  }
}
