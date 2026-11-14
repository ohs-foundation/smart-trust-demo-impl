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
package dev.ohs.trustdemo.trustkit

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import who.smart.trust.core.Hcert
import who.smart.trust.vhl.SignatureRequest

class Rfc9421InteropTest {

  @Test
  fun nodeSignedRequestVerifiesOnTheJvmAndChainsToTheAnchor() {
    val r = loadSignedRequest()
    val base = Hcert.vhl.buildSignatureBase(r.toSignatureRequest())

    assertEquals(r.headers.getValue("Signature-Input"), base.signatureInput)
    assertEquals(r.headers.getValue("Content-Digest"), base.contentDigest)
    assertEquals(kid(r.certificate), r.param("keyid"))
    assertTrue(
      Signature.getInstance("SHA256withECDSAinP1363Format").run {
        initVerify(r.certificate.publicKey)
        update(base.base)
        verify(r.signatureBytes)
      }
    )
    val anchor = TestKeys.certificate(StandIn.XXR_CLINIC_ANCHOR)
    val params =
      PKIXParameters(setOf(TrustAnchor(anchor, null))).apply { isRevocationEnabled = false }
    CertPathValidator.getInstance("PKIX")
      .validate(
        CertificateFactory.getInstance("X.509").generateCertPath(listOf(r.certificate)),
        params,
      )
  }

  @Test
  fun aTamperedBodyFails() {
    val r = loadSignedRequest().withBodyByteFlipped(0)
    val base = Hcert.vhl.buildSignatureBase(r.toSignatureRequest())

    assertNotEquals(r.headers.getValue("Content-Digest"), base.contentDigest)
    assertTrue(
      !Signature.getInstance("SHA256withECDSAinP1363Format").run {
        initVerify(r.certificate.publicKey)
        update(base.base)
        verify(r.signatureBytes)
      }
    )
  }

  private class SignedRequest(
    val method: String,
    val authority: String,
    val path: String,
    val body: ByteArray,
    val headers: Map<String, String>,
  ) {
    val certificate: X509Certificate =
      CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(sfBytes(headers.getValue("Client-Cert"))))
        as X509Certificate

    val signatureBytes: ByteArray = sfBytes(headers.getValue("Signature").substringAfter("sig1="))

    fun param(name: String): String =
      Regex(""";$name=("?)([^";]+)\1""").find(headers.getValue("Signature-Input"))!!.groupValues[2]

    fun toSignatureRequest() =
      SignatureRequest(
        method = method,
        authority = authority,
        path = path,
        contentType = headers.getValue("Content-Type"),
        body = body,
        keyid = param("keyid"),
        alg = param("alg"),
        created = param("created").toLong(),
      )

    fun withBodyByteFlipped(index: Int) =
      SignedRequest(
        method,
        authority,
        path,
        body.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() },
        headers,
      )
  }

  private fun loadSignedRequest(): SignedRequest {
    val json =
      Json.parseToJsonElement(
          checkNotNull(javaClass.getResource("/rfc9421/signed-request.json")).readText()
        )
        .jsonObject
    fun text(key: String) = json.getValue(key).jsonPrimitive.content
    return SignedRequest(
      text("method"),
      text("authority"),
      text("path"),
      Base64.getDecoder().decode(text("body")),
      (json.getValue("headers") as JsonObject).mapValues { it.value.jsonPrimitive.content },
    )
  }

  private companion object {
    fun sfBytes(item: String): ByteArray =
      Base64.getDecoder().decode(item.trim().removeSurrounding(":"))

    fun kid(certificate: X509Certificate): String =
      Base64.getEncoder()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.encoded).copyOf(8))
  }
}
