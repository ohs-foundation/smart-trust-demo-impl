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

import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import who.smart.trust.HcertResult
import who.smart.trust.core.DEFAULT_LIMITS
import who.smart.trust.core.Hcert
import who.smart.trust.dvc.DvcMin
import who.smart.trust.dvc.DvcMinVaccineDetails
import who.smart.trust.issuer.IssueRequest
import who.smart.trust.issuer.Validity
import who.smart.trust.payloads.Payload
import who.smart.trust.types.CoseAlgorithm

/** The key and certificate a card is signed with. */
class CardKey(val privateKey: PrivateKey, val certificate: X509Certificate) {
  companion object {
    /** The committed XXR card key under `testkit/`; test only. */
    val testkit: CardKey
      get() = CardKey(TestKeys.privateKey(StandIn.XXR_CARD), TestKeys.certificate(StandIn.XXR_CARD))

    /** A key from the PEM texts of a mounted key and its certificate. */
    fun fromPem(keyPem: String, certificatePem: String): CardKey =
      CardKey(
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(TestKeys.pemDer(keyPem))),
        CertificateFactory.getInstance("X.509")
          .generateCertificate(ByteArrayInputStream(TestKeys.pemDer(certificatePem)))
          as X509Certificate,
      )
  }
}

/** Signs WHO DVC cards (HC1 text) through hcert-core's issuer. */
object CardSigner {
  private const val PRODUCT_SYSTEM =
    "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVaccineProduct"
  private const val NATIONALITY = "XXR"
  private const val PARTICIPANT = "XXR"
  private const val ISSUING_COUNTRY = "XR"
  private const val VALID_DAYS = 365L

  /** The card for [dose], its dates moved by [clock]; valid from the shifted dose time. */
  fun sign(
    dose: Immunization,
    patient: Patient,
    clock: StoryClock = StoryClock.NONE,
    key: CardKey = CardKey.testkit,
  ): String {
    val givenAt = OffsetDateTime.parse(dose.occurrence.asDateTime()!!.value.value.toString())
    val issuedAt = givenAt.plusDays(clock.shiftDays())
    val request =
      IssueRequest(
        payloads = listOf(Payload.DvcMin(dvc(dose, patient, clock, givenAt.toLocalDate()))),
        iss = ISSUING_COUNTRY,
        validity =
          Validity(
            iat = issuedAt.toEpochSecond(),
            exp = issuedAt.plus(VALID_DAYS, ChronoUnit.DAYS).toEpochSecond(),
          ),
        dscDer = key.certificate.encoded,
        alg = CoseAlgorithm.ES256,
        allowDevelopmentSubclaims = true,
      )
    val pending = ok(Hcert.issuer.prepare(request, Hcert.subclaims.newRegistry()))
    val signature =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(key.privateKey)
        update(pending.toBeSigned())
        sign()
      }
    return ok(Hcert.issuer.finish(pending, signature)).hc1
  }

  /** The vaccination date a card carries, read without checking its signature or trust. */
  fun vaccinationDate(hc1: String): LocalDate = LocalDate.parse(vaccine(hc1).dv)

  /** The product code a card carries, read without checking its signature or trust. */
  fun product(hc1: String): String = vaccine(hc1).vp

  private fun vaccine(hc1: String): DvcMinVaccineDetails {
    val card = ok(Hcert.verifier.readUnverified(hc1, Hcert.subclaims.newRegistry(), DEFAULT_LIMITS))
    return card.entries.firstNotNullOf { it.payload as? Payload.DvcMin }.value.vx
  }

  private fun dvc(
    dose: Immunization,
    patient: Patient,
    clock: StoryClock,
    given: LocalDate,
  ): DvcMin {
    val name = patient.name.first()
    return DvcMin(
      n =
        (name.given.mapNotNull { it.value } + listOfNotNull(name.family?.value)).joinToString(" "),
      dob = clock.real(LocalDate.parse(patient.birthDate!!.value.toString())).toString(),
      s = patient.gender!!.value!!.toString(),
      nt = NATIONALITY,
      vx =
        DvcMinVaccineDetails(
          vp = dose.vaccineCode.coding.first { it.system?.value == PRODUCT_SYSTEM }.code!!.value!!,
          dv = clock.real(given).toString(),
          `is` = PARTICIPANT,
          bo = dose.lotNumber!!.value!!,
        ),
      v = "1.0.0",
      extensions = emptyList(),
    )
  }

  private fun <T, E> ok(result: HcertResult<T, E>): T =
    when (result) {
      is HcertResult.Ok -> result.value
      is HcertResult.Err -> error("card issuance failed: ${result.error}")
    }
}
