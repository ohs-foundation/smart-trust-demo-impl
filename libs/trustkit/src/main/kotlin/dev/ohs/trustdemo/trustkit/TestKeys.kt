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
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Fictional stand-ins for the demo's signers. [UNKNOWN_LINK] is never published to a trust list, so
 * a link it signs is one no verifier can place.
 */
enum class StandIn(val domain: String, val participant: String) {
  XXR_CARD("DEMO-DVC", "XXR"),
  XXU_LINK("DEMO-VHL", "XXU"),
  XXR_CLINIC_ANCHOR("DEMO-VHL", "XXR"),
  UNKNOWN_LINK("DEMO-VHL", "XXU"),
}

/** The committed test keys under `testkit/`; test only, never production material. */
object TestKeys {
  internal const val GDHCN_SIGNER = "GDHCN_SIGNER"

  fun privateKey(s: StandIn): PrivateKey = privateKey(s.name)

  fun certificate(s: StandIn): X509Certificate = certificate(s.name)

  /** First 8 bytes of the certificate's SHA-256, standard base64: the form a list's `kid` takes. */
  fun kid(s: StandIn): String = kidOf(certificate(s))

  internal fun kidOf(cert: X509Certificate): String =
    Base64.getEncoder().encodeToString(sha256(cert.encoded).copyOf(8))

  fun gdhcnSignerDidDocument(): String = testkitText("gdhcn-signer-did.json")

  internal fun privateKey(name: String): PrivateKey =
    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pemBody("$name.key.pem")))

  internal fun certificate(name: String): X509Certificate =
    CertificateFactory.getInstance("X.509")
      .generateCertificate(ByteArrayInputStream(pemBody("$name.crt.pem"))) as X509Certificate

  internal fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  private fun pemBody(name: String): ByteArray = pemDer(testkitText(name))

  internal fun pemDer(pem: String): ByteArray =
    Base64.getMimeDecoder()
      .decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))

  internal fun testkitText(name: String): String =
    checkNotNull(TestKeys::class.java.getResource("/$name")) { "testkit/$name is missing" }
      .readText()
}
