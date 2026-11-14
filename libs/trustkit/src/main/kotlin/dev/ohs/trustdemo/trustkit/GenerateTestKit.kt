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

import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import org.bouncycastle.asn1.x500.X500Name

private val CREATED = Instant.parse("2026-11-14T00:00:00Z")
private const val NONCE = "tRuStDeMoLoCaLtEsTlIsTnOnCe00001"

private val SUBJECTS =
  mapOf(
    "XXR_CARD" to "C=XR,O=Trust Demo Test Only,CN=XXR Card Issuer",
    "XXK_LINK" to "C=XK,O=Trust Demo Test Only,CN=XXK Link Issuer",
    "XXR_CLINIC_ANCHOR" to "C=XR,O=Trust Demo Test Only,CN=XXR Clinic Anchor",
    "UNKNOWN_LINK" to "C=XK,O=Trust Demo Test Only,CN=Unlisted Link Issuer",
    TestKeys.GDHCN_SIGNER to "C=XX,O=Trust Demo Test Only,CN=GDHCN Stand-in Signer",
  )

/**
 * Writes `testkit/` into the directory given as the first argument: a key and a self-signed
 * certificate per stand-in and for the GDHCN signer, the signer's DID document and the signed list.
 * Keys and certificates already present are kept, so the list is rebuilt from the same keys.
 */
fun main(args: Array<String>) {
  val out = File(args.single()).apply { mkdirs() }
  val notBefore = Instant.parse("2026-01-01T00:00:00Z")
  val notAfter = notBefore.plus(20 * 365L, ChronoUnit.DAYS)

  for ((name, subject) in SUBJECTS) {
    val keyFile = File(out, "$name.key.pem")
    val certFile = File(out, "$name.crt.pem")
    if (keyFile.exists() && certFile.exists()) continue
    val pair = ClinicCertificates.newKeyPair()
    keyFile.writeText(pem("PRIVATE KEY", pair.private.encoded))
    certFile.writeText(
      pem(
        "CERTIFICATE",
        ClinicCertificates.certificate(
            issuer = X500Name(subject).encoded,
            subject = subject,
            subjectKey = pair,
            signer = pair.private,
            notBefore = notBefore,
            notAfter = notAfter,
            ca = name == "XXR_CLINIC_ANCHOR",
          )
          .encoded,
      )
    )
  }

  val signerKey = privateKey(File(out, "${TestKeys.GDHCN_SIGNER}.key.pem"))
  val certificates = StandIn.entries.associateWith { certificate(File(out, "${it.name}.crt.pem")) }
  File(out, "gdhcn-signer-did.json")
    .writeText(
      TrustListWriter.signerDidDocument(
        certificate(File(out, "${TestKeys.GDHCN_SIGNER}.crt.pem")).publicKey as ECPublicKey
      ) + "\n"
    )
  File(out, "local-test-list.json")
    .writeText(TrustListWriter.generate(certificates, signerKey, CREATED, NONCE) + "\n")
}

private fun pem(label: String, der: ByteArray): String =
  "-----BEGIN $label-----\n" +
    Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
    "\n-----END $label-----\n"

private fun pemBody(file: File): ByteArray =
  Base64.getMimeDecoder()
    .decode(file.readLines().filterNot { it.startsWith("-----") }.joinToString(""))

private fun privateKey(file: File): PrivateKey =
  KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pemBody(file)))

private fun certificate(file: File): X509Certificate =
  CertificateFactory.getInstance("X.509").generateCertificate(pemBody(file).inputStream())
    as X509Certificate
