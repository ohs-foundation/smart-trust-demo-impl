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
package dev.ohs.trustdemo.onesa.data.hcert

import dev.ohs.trustdemo.trustkit.StandIn
import dev.ohs.trustdemo.trustkit.TestKeys
import java.security.Signature
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert
import who.smart.trust.dvc.DvcMin
import who.smart.trust.dvc.DvcMinVaccineDetails
import who.smart.trust.issuer.IssueRequest
import who.smart.trust.issuer.Validity
import who.smart.trust.payloads.Payload
import who.smart.trust.types.CoseAlgorithm

/** Who a card is for, and the lot and day of its dose. */
data class DvcHolder(
  val name: String = "Test Child",
  val sex: String = "female",
  val given: String = "2026-03-01",
  val lot: String = "LOT1",
)

/**
 * DVC cards signed with a chosen stand-in key, for checking which trust store accepts which key.
 */
object SignedCards {
  fun dvc(
    signer: StandIn,
    nowEpochSeconds: Long,
    product: String = "a3K3X000006MiiXUAS",
    dob: String = "2026-01-01",
    holder: DvcHolder = DvcHolder(),
  ): String {
    val request =
      IssueRequest(
        payloads = listOf(Payload.DvcMin(dvcMin(product, dob, holder))),
        iss = "XR",
        validity = Validity(iat = nowEpochSeconds, exp = nowEpochSeconds + 86_400),
        dscDer = TestKeys.certificate(signer).encoded,
        alg = CoseAlgorithm.ES256,
        allowDevelopmentSubclaims = true,
      )
    val pending = ok(Hcert.issuer.prepare(request, Hcert.subclaims.newRegistry()))
    val signature =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(TestKeys.privateKey(signer))
        update(pending.toBeSigned())
        sign()
      }
    return ok(Hcert.issuer.finish(pending, signature)).hc1
  }

  private fun dvcMin(product: String, dob: String, holder: DvcHolder) =
    DvcMin(
      n = holder.name,
      dob = dob,
      s = holder.sex,
      nt = "XXR",
      vx = DvcMinVaccineDetails(vp = product, dv = holder.given, `is` = "XXR", bo = holder.lot),
      v = "1.0.0",
      extensions = emptyList(),
    )

  private fun <T, E> ok(result: HcertResult<T, E>): T =
    when (result) {
      is HcertResult.Ok -> result.value
      is HcertResult.Err -> error("card issuance failed: ${result.error}")
    }
}
