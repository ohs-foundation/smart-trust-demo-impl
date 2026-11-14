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
import java.security.Signature
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert
import who.smart.trust.dvc.DvcMin
import who.smart.trust.dvc.DvcMinVaccineDetails
import who.smart.trust.issuer.IssueRequest
import who.smart.trust.issuer.Validity
import who.smart.trust.payloads.Payload
import who.smart.trust.types.CoseAlgorithm

/** Signs WHO DVC cards (HC1 text) through hcert-core's issuer, always with the XXR card key. */
object CardSigner {
  private const val PRODUCT_SYSTEM =
    "http://smart.who.int/trust-demo/CodeSystem/TrustDemoVaccineProduct"
  private const val NATIONALITY = "XXR"
  private const val ISSUING_COUNTRY = "XR"
  private const val VALID_DAYS = 365L

  /** The card for [dose], its dates moved by [clock]; valid from the shifted dose time. */
  fun sign(dose: Immunization, patient: Patient, clock: StoryClock): String {
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
        dscDer = TestKeys.certificate(StandIn.XXR_CARD).encoded,
        alg = CoseAlgorithm.ES256,
        allowDevelopmentSubclaims = true,
      )
    val pending = ok(Hcert.issuer.prepare(request, Hcert.subclaims.newRegistry()))
    val signature =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(TestKeys.privateKey(StandIn.XXR_CARD))
        update(pending.toBeSigned())
        sign()
      }
    return ok(Hcert.issuer.finish(pending, signature)).hc1
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
          `is` = patient.managingOrganization!!.reference!!.value!!.substringAfter('/'),
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
