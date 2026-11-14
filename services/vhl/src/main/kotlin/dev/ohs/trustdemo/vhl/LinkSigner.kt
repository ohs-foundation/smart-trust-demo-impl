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
package dev.ohs.trustdemo.vhl

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert
import who.smart.trust.issuer.IssueRequest
import who.smart.trust.issuer.Validity
import who.smart.trust.payloads.Payload
import who.smart.trust.types.CoseAlgorithm
import who.smart.trust.vhl.VhlPayload

/** Signs a link's HCERT as the link issuer [country]; the key is a mounted test key. */
class LinkSigner(
  private val key: PrivateKey,
  private val certificateDer: ByteArray,
  private val country: String,
) {
  fun sign(link: VhlPayload, issuedAt: Instant, expires: Instant): String {
    val request =
      IssueRequest(
        payloads = listOf(Payload.Vhl(link)),
        iss = country,
        validity = Validity(iat = issuedAt.epochSecond, exp = expires.epochSecond),
        dscDer = certificateDer,
        alg = CoseAlgorithm.ES256,
        allowDevelopmentSubclaims = true,
      )
    val pending = ok(Hcert.issuer.prepare(request, Hcert.subclaims.newRegistry()))
    val signature =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(key)
        update(pending.toBeSigned())
        sign()
      }
    return ok(Hcert.issuer.finish(pending, signature)).hc1
  }

  companion object {
    fun fromPem(keyPem: String, certificatePem: String, country: String): LinkSigner {
      val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pemBody(keyPem)))
      val certificate =
        CertificateFactory.getInstance("X.509")
          .generateCertificate(ByteArrayInputStream(pemBody(certificatePem)))
      return LinkSigner(key, certificate.encoded, country)
    }

    private fun pemBody(pem: String): ByteArray =
      Base64.getMimeDecoder()
        .decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))

    private fun <T, E> ok(result: HcertResult<T, E>): T =
      when (result) {
        is HcertResult.Ok -> result.value
        is HcertResult.Err -> error("link issuance failed: ${result.error}")
      }
  }
}
