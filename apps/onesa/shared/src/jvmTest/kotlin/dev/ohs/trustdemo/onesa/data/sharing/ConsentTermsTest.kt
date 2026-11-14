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
package dev.ohs.trustdemo.onesa.data.sharing

import dev.ohs.trustdemo.onesa.util.FhirJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConsentTermsTest {
  private val ig = File("../../../ig/resources/CodeSystem-TrustDemoVhlConsent.json").readText()
  private val bundled =
    File("src/commonMain/composeResources/files/consent/CodeSystem-TrustDemoVhlConsent.json")
      .readText()

  private fun json(text: String): JsonObject = FhirJson.instance.parseToJsonElement(text).jsonObject

  @Test
  fun theConsentTermsAreTheIgs() {
    assertEquals(json(ig), json(bundled))

    val terms = ConsentTerms.parse(bundled)
    val concept = json(ig).getValue("concept").jsonArray.single().jsonObject

    assertEquals(
      ConsentTerm(
        code = concept.getValue("code").jsonPrimitive.content,
        display = concept.getValue("display").jsonPrimitive.content,
        definition = concept.getValue("definition").jsonPrimitive.content,
      ),
      terms.single(),
    )
    assertEquals("CONSENT-VHL-TREATMENT", terms.single().code)
  }
}
