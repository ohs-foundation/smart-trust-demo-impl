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
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The GDHCN-style trust list the demo's apps load, signed by the GDHCN stand-in signer. */
object TrustListWriter {
  internal const val LIST_ID = "did:web:trust.gdhcn.example:v2:trustlist"
  internal const val SIGNER_ID = "did:web:trust.gdhcn.example:signing"

  private val published =
    listOf(
      StandIn.XXR_CARD to "DSC",
      StandIn.XXK_LINK to "DSC",
      StandIn.XXR_CLINIC_ANCHOR to "CSCA",
    )

  /**
   * The committed list: verifies against [TestKeys.gdhcnSignerDidDocument] and holds three keys.
   */
  fun localTestList(): String = TestKeys.testkitText("local-test-list.json")

  internal fun generate(
    certificates: Map<StandIn, X509Certificate>,
    signerKey: PrivateKey,
    created: Instant,
    nonce: String,
  ): String =
    DidProofSigner.sign(unsigned(certificates).toString(), signerKey, SIGNER_ID, created, nonce)

  /** The signer's DID document: one P-256 JsonWebKey2020, no certificate. */
  internal fun signerDidDocument(publicKey: ECPublicKey): String =
    buildJsonObject {
        putJsonArray("@context") {
          add(JsonPrimitive("https://www.w3.org/ns/did/v1"))
          add(JsonPrimitive("https://w3id.org/security/suites/jws-2020/v1"))
        }
        put("id", SIGNER_ID)
        put("controller", SIGNER_ID)
        putJsonArray("verificationMethod") {
          add(
            buildJsonObject {
              put("id", SIGNER_ID)
              put("type", "JsonWebKey2020")
              put("controller", SIGNER_ID)
              putJsonObject("publicKeyJwk") {
                put("kty", "EC")
                put("crv", "P-256")
                put("x", coordinate(publicKey.w.affineX))
                put("y", coordinate(publicKey.w.affineY))
              }
            }
          )
        }
      }
      .toString()

  private fun unsigned(certificates: Map<StandIn, X509Certificate>): JsonObject = buildJsonObject {
    putJsonArray("@context") {
      add(JsonPrimitive("https://www.w3.org/ns/did/v1"))
      add(JsonPrimitive("https://w3id.org/security/suites/jws-2020/v1"))
      add(JsonPrimitive("https://smart.who.int/trust/tng-context/v1-DEV.jsonld"))
    }
    put("id", LIST_ID)
    put("controller", LIST_ID)
    put(
      "verificationMethod",
      JsonArray(published.map { (s, usage) -> method(s, usage, certificates.getValue(s)) }),
    )
  }

  private fun method(s: StandIn, usage: String, cert: X509Certificate): JsonObject {
    val key = cert.publicKey as ECPublicKey
    val kid = TestKeys.kidOf(cert)
    return buildJsonObject {
      put("id", "$LIST_ID:${s.domain}:${s.participant}:$usage#$kid")
      put("type", "JsonWebKey2020")
      put("controller", LIST_ID)
      putJsonObject("publicKeyJwk") {
        put("kty", "EC")
        put("kid", kid)
        putJsonArray("x5c") { add(JsonPrimitive(Base64.getEncoder().encodeToString(cert.encoded))) }
        put("crv", "P-256")
        put("x", coordinate(key.w.affineX))
        put("y", coordinate(key.w.affineY))
      }
      putJsonObject("domain") { put("code", "#${s.domain}") }
      putJsonObject("participant") { put("code", "#${s.participant}") }
      putJsonObject("keyusage") { put("code", "#$usage") }
    }
  }

  private fun coordinate(v: BigInteger): String {
    val bytes =
      v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(ByteArray(32 - bytes.size) + bytes)
  }
}
