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
package dev.ohs.trustdemo.hcert

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert
import who.smart.trust.dvc.DvcMin
import who.smart.trust.dvc.DvcMinVaccineDetails
import who.smart.trust.issuer.IssueRequest
import who.smart.trust.issuer.Validity
import who.smart.trust.payloads.Payload
import who.smart.trust.types.CoseAlgorithm

/** A certifying (CSCA) key on a trust list vouches for signers; it must never sign a card. */
class CertifyingKeysTest {
  private val engine = runBlocking { loadHcertEngine() }
  private val now = 1_800_000_000L

  private val list: String by lazy {
    val root = Json.parseToJsonElement(Vectors.resource("/local-test-list.json")).jsonObject
    val methods = root["verificationMethod"]!!.jsonArray.map { it.jsonObject }
    val dsc = methods.first { ":DEMO-DVC:XXR:DSC" in it["id"]!!.jsonPrimitive.content }
    val csca = methods.first { ":XXR:CSCA" in it["id"]!!.jsonPrimitive.content }
    val inDvc =
      JsonObject(csca + ("domain" to JsonObject(mapOf("code" to JsonPrimitive("#DEMO-DVC")))))
    JsonObject(root + ("verificationMethod" to JsonArray(listOf(dsc, inDvc)))).toString()
  }

  @Test
  fun `a card signed by a CSCA key on the list is not trusted`() {
    val trust = engine.newTrustStore().apply { addDidDocument(list) }
    val failure =
      assertIs<VerificationResult.Failed>(engine.verify(card("XXR_CLINIC_ANCHOR"), trust, now + 60))
        .failure
    assertEquals(
      VerifyStep.TRUST_LOOKUP to FailureReason.KID_NOT_FOUND,
      failure.step to failure.reason,
    )
  }

  @Test
  fun `a card signed by the DSC from the same list still verifies`() {
    val trust = engine.newTrustStore().apply { assertEquals(1, addDidDocument(list)) }
    assertIs<VerificationResult.Verified>(engine.verify(card("XXR_CARD"), trust, now + 60))
  }

  @Test
  fun `the DEV list drops only its CSCA entries`() {
    val csca = Regex("\"code\"\\s*:\\s*\"#CSCA\"").findAll(Vectors.devTrustList).count()
    val total = DidDocuments.entryCount(Vectors.devTrustList)
    val trust = engine.newTrustStore()
    val added = trust.addDidDocument(Vectors.devTrustList)
    assertTrue(csca > 0, "the DEV list holds CSCA entries")
    assertEquals(total - csca, added)
    assertEquals(added, trust.size())
  }

  private fun card(name: String): String {
    val der = pem("$name.crt.pem")
    val cert =
      CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
    val key =
      KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pem("$name.key.pem")))
    val request =
      IssueRequest(
        payloads = listOf(Payload.DvcMin(dvc())),
        iss = "XR",
        validity = Validity(iat = now, exp = now + 86_400),
        dscDer = cert.encoded,
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

  private fun dvc() =
    DvcMin(
      n = "Test Child",
      dob = "2026-01-01",
      s = "female",
      nt = "XXR",
      vx =
        DvcMinVaccineDetails(
          vp = "a3K3X000006MiiXUAS",
          dv = "2026-03-01",
          `is` = "XXR",
          bo = "LOT1",
        ),
      v = "1.0.0",
      extensions = emptyList(),
    )

  private fun pem(resource: String): ByteArray =
    Base64.getMimeDecoder()
      .decode(
        Vectors.resource("/$resource").lines().filterNot { it.startsWith("-----") }.joinToString("")
      )

  private fun <T, E> ok(result: HcertResult<T, E>): T =
    when (result) {
      is HcertResult.Ok -> result.value
      is HcertResult.Err -> error("card issuance failed: ${result.error}")
    }
}
