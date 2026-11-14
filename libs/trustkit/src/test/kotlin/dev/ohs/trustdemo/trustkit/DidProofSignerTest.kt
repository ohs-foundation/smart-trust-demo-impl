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

import kotlin.test.Test
import kotlin.test.assertEquals
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert

class DidProofSignerTest {
  private fun golden(name: String) =
    checkNotNull(javaClass.getResource("/golden/$name")) { "missing $name" }.readText()

  private val whoSigner =
    "did:web:raw.githubusercontent.com:WorldHealthOrganization:tng-participants-dev:main:WHO:signing:DID"

  @Test
  fun documentNQuadsMatchTheGoldenFile() {
    val doc =
      DidProofSigner.parse(golden("XXR-DSC-did-2026-10-23.json")).filterKeys { it != "proof" }
    assertEquals(golden("XXR-DSC-did.doc.nq"), DidProofSigner.documentNQuads(doc))
  }

  @Test
  fun proofOptionsNQuadsMatchTheGoldenFile() {
    val proof =
      mapOf(
        "type" to "JsonWebSignature2020",
        "created" to "2026-10-22T12:00:16Z",
        "nonce" to "z9n9PBzMPjpF5duCo4BLb4q2i9AGVA1J",
        "proofPurpose" to "assertionMethod",
        "verificationMethod" to whoSigner,
      )
    assertEquals(golden("XXR-DSC-did.proof-options.nq"), DidProofSigner.proofOptionsNQuads(proof))
  }

  @Test
  fun theWhoSignedDocumentStillVerifiesWithTheRealLibrary() {
    assertEquals(
      HcertResult.Ok(Unit),
      Hcert.trust.verifyDidProof(
        golden("XXR-DSC-did-2026-10-23.json"),
        golden("WHO-signing-did.json"),
      ),
    )
  }
}
