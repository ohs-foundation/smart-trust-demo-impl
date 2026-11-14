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

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object ClinicCertificates {
  /**
   * A fresh viewer key and a certificate for it issued by [issuer], the clinic anchor by default.
   */
  fun issueClinicViewer(
    subject: String = "CN=clinic-viewer.kesaro.example",
    issuer: StandIn = StandIn.XXR_CLINIC_ANCHOR,
    validFor: Duration = Duration.ofDays(365),
    ca: Boolean = false,
  ): Pair<PrivateKey, X509Certificate> {
    val key = newKeyPair()
    val anchor = TestKeys.certificate(issuer)
    val now = Instant.now()
    val cert =
      certificate(
        issuer = anchor.subjectX500Principal.encoded,
        subject = subject,
        subjectKey = key,
        signer = TestKeys.privateKey(issuer),
        notBefore = now.minus(1, ChronoUnit.DAYS),
        notAfter = now.plus(validFor),
        ca = ca,
      )
    return key.private to cert
  }

  internal fun newKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC")
      .apply { initialize(ECGenParameterSpec("secp256r1")) }
      .generateKeyPair()

  internal fun certificate(
    issuer: ByteArray,
    subject: String,
    subjectKey: KeyPair,
    signer: PrivateKey,
    notBefore: Instant,
    notAfter: Instant,
    ca: Boolean,
  ): X509Certificate {
    val builder =
      JcaX509v3CertificateBuilder(
        X500Name.getInstance(issuer),
        BigInteger.valueOf(Instant.now().toEpochMilli()),
        Date.from(notBefore),
        Date.from(notAfter),
        X500Name(subject),
        subjectKey.public,
      )
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
    builder.addExtension(
      Extension.keyUsage,
      true,
      KeyUsage(if (ca) KeyUsage.keyCertSign else KeyUsage.digitalSignature),
    )
    return JcaX509CertificateConverter()
      .getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(signer)))
  }
}
