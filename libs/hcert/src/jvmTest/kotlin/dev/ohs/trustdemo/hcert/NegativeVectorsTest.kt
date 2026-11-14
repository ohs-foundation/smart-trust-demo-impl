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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import who.smart.trust.core.Hcert

/**
 * smart-trust-tools' negative vectors: each case must fail at the step and for the reason it names,
 * and never verify.
 */
class NegativeVectorsTest {
  private val engine = runBlocking { loadHcertEngine() }
  private val vectors =
    Json.parseToJsonElement(Vectors.resource("/negative/negative-vectors.json")).jsonObject
  private val defaults = vectors.getValue("defaults").jsonObject
  private val anchor = Vectors.resource("/vectors/dvc-qr-example-XXA-dsc.pem")

  @Test
  fun `every negative vector fails at the step and reason it names`() {
    val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
    assertEquals(15, cases.size)
    for (case in cases) {
      val id = case.getValue("id").jsonPrimitive.content
      val trust = engine.newTrustStore()
      if (case["trust"] == null) trust.addPem(anchor)
      else assertTrue((case["trust"] as JsonArray).isEmpty())
      val now = (case["now"] ?: defaults.getValue("now")).jsonPrimitive.long
      val options = VerifyOptions(defaults.getValue("clockSkewSeconds").jsonPrimitive.int)
      val failure =
        assertIs<VerificationResult.Failed>(engine.verify(hc1Of(case), trust, now, options), id)
          .failure
      val expected = case.getValue("expect").jsonObject
      assertEquals(
        expected.getValue("step").jsonPrimitive.content to
          expected.getValue("reason").jsonPrimitive.content,
        failure.step.name to failure.reason.name,
        id,
      )
    }
  }

  private fun hc1Of(case: JsonObject): String =
    case["hc1"]?.jsonPrimitive?.content
      ?: Hcert.codec.toHc1(
        case
          .getValue("coseHex")
          .jsonPrimitive
          .content
          .chunked(2)
          .map { it.toInt(16).toByte() }
          .toByteArray()
      )
}
