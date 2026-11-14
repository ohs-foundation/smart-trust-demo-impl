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

import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Clock
import java.util.Base64
import java.util.Date
import javax.naming.ldap.LdapName
import kotlin.math.abs
import who.smart.trust.core.Hcert
import who.smart.trust.vhl.SignatureRequest

private const val ALGORITHM = "ecdsa-p256-sha256"
private const val MAX_SKEW_SECONDS = 300L
private const val MAX_HEADER = 8192
private const val ES256_SIGNATURE_BYTES = 64
private val SIGNATURE_INPUT =
  Regex(
    """sig1=\([^)]{0,200}\);created=(\d{1,12});keyid="([A-Za-z0-9+/=]{1,32})";alg="([a-z0-9-]{1,32})""""
  )

/** A request exactly as it arrived: method, path with query, headers, raw body. */
class ReceivedRequest(
  val method: String,
  val target: String,
  val body: ByteArray?,
  val header: (String) -> String?,
)

/** A request that failed verification; [message] never repeats what the request carried. */
class RequestRefused(message: String, val status: HttpStatusCode = HttpStatusCode.Unauthorized) :
  Exception(message)

/**
 * Verifies the clinic's RFC 9421 signature over a request for [authority], the public authority the
 * clinic signed, whatever `Host` a proxy forwards. The certificate must chain to the clinic anchor.
 */
class SignedRequestVerifier(
  private val authority: String,
  private val anchor: ClinicAnchor,
  private val clock: Clock,
) {
  fun verify(request: ReceivedRequest): Requester {
    val anchorCertificate =
      anchor.current()
        ?: throw RequestRefused("no trust list is loaded", HttpStatusCode.ServiceUnavailable)
    val input = request.required("Signature-Input")
    val signature = request.required("Signature")
    val certificate = certificateOf(request.required("Client-Cert"))
    if (request.body != null) {
      request.required("Content-Type")
      request.required("Content-Digest")
    }
    val parameters = SIGNATURE_INPUT.matchEntire(input) ?: refuse("Signature-Input is malformed")
    val (created, keyid, algorithm) = parameters.destructured
    if (algorithm != ALGORITHM) refuse("the signature algorithm is not $ALGORITHM")

    val rebuilt =
      Hcert.vhl.buildSignatureBase(
        SignatureRequest(
          method = request.method,
          authority = authority,
          path = request.target,
          contentType = request.header("Content-Type"),
          body = request.body,
          keyid = keyid,
          alg = algorithm,
          created = created.toLong(),
        )
      )
    if (rebuilt.signatureInput != input) refuse("Signature-Input differs from the request")
    if (rebuilt.contentDigest != request.header("Content-Digest")) {
      refuse("Content-Digest differs from the body")
    }
    if (!signature.startsWith("sig1=:") || !signature.endsWith(":"))
      refuse("Signature is malformed")
    val signatureBytes = decode(signature.removePrefix("sig1="))
    if (signatureBytes.size != ES256_SIGNATURE_BYTES) refuse("Signature is malformed")
    val verified =
      Signature.getInstance("SHA256withECDSAinP1363Format").run {
        initVerify(certificate.publicKey)
        update(rebuilt.base)
        verify(signatureBytes)
      }
    if (!verified) refuse("the signature does not verify")
    val kid = kidOf(certificate)
    if (keyid != kid) refuse("keyid is not the certificate's")
    if (!chainsTo(certificate, anchorCertificate))
      refuse("the certificate does not chain to the anchor")
    if (abs(clock.instant().epochSecond - created.toLong()) > MAX_SKEW_SECONDS) {
      refuse("created is outside the allowed window")
    }
    val name = commonName(certificate) ?: refuse("the certificate names no subject")
    return Requester(name, kid)
  }

  private fun chainsTo(certificate: X509Certificate, anchor: X509Certificate): Boolean =
    try {
      val parameters =
        PKIXParameters(setOf(TrustAnchor(anchor, null))).apply {
          isRevocationEnabled = false
          date = Date.from(clock.instant())
        }
      CertPathValidator.getInstance("PKIX")
        .validate(
          CertificateFactory.getInstance("X.509").generateCertPath(listOf(certificate)),
          parameters,
        )
      true
    } catch (e: GeneralSecurityException) {
      false
    }

  private fun certificateOf(header: String): X509Certificate =
    try {
      CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(decode(header))) as X509Certificate
    } catch (e: Exception) {
      refuse("Client-Cert is not a certificate")
    }

  private fun decode(item: String): ByteArray =
    try {
      Base64.getDecoder().decode(item.removeSurrounding(":"))
    } catch (e: IllegalArgumentException) {
      refuse("a header is not base64")
    }

  private fun commonName(certificate: X509Certificate): String? =
    LdapName(certificate.subjectX500Principal.name)
      .rdns
      .firstOrNull { it.type.equals("CN", ignoreCase = true) }
      ?.value
      ?.toString()
      ?.ifBlank { null }

  private fun ReceivedRequest.required(name: String): String =
    header(name)?.takeIf { it.length <= MAX_HEADER } ?: refuse("$name is missing")

  private fun refuse(reason: String): Nothing = throw RequestRefused(reason)

  private fun kidOf(certificate: X509Certificate): String =
    Base64.getEncoder()
      .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.encoded).copyOf(8))
}
