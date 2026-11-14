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

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import who.smart.trust.core.Hcert
import who.smart.trust.vhl.SignatureRequest

const val AUTHORITY = "vhl.onesa.test:8443"
const val FORM = "application/x-www-form-urlencoded"

class Signed(val path: String, val body: ByteArray?, val headers: Map<String, String>) {
  fun copy(body: ByteArray? = this.body, headers: Map<String, String> = this.headers) =
    Signed(path, body, headers)
}

fun signRequest(
  method: String,
  path: String,
  body: ByteArray?,
  identity: Pair<PrivateKey, X509Certificate>,
  created: Long,
  authority: String = AUTHORITY,
): Signed {
  val (key, certificate) = identity
  val contentType = if (body == null) null else FORM
  val rebuilt =
    Hcert.vhl.buildSignatureBase(
      SignatureRequest(
        method = method,
        authority = authority,
        path = path,
        contentType = contentType,
        body = body,
        keyid = kid(certificate),
        alg = "ecdsa-p256-sha256",
        created = created,
      )
    )
  val signature =
    Signature.getInstance("SHA256withECDSAinP1363Format").run {
      initSign(key)
      update(rebuilt.base)
      sign()
    }
  val headers = buildMap {
    contentType?.let { put("Content-Type", it) }
    rebuilt.contentDigest?.let { put("Content-Digest", it) }
    put("Signature-Input", rebuilt.signatureInput)
    put("Signature", "sig1=:${Base64.getEncoder().encodeToString(signature)}:")
    put("Client-Cert", ":${Base64.getEncoder().encodeToString(certificate.encoded)}:")
  }
  return Signed(path, body, headers)
}

fun kid(certificate: X509Certificate): String =
  Base64.getEncoder()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.encoded).copyOf(8))
