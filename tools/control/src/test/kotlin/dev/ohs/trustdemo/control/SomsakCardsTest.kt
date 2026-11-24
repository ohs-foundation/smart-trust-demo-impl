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
package dev.ohs.trustdemo.control

import dev.ohs.trustdemo.trustkit.CardSigner
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PREQUAL = "http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualProductIds"

class SomsakCardsTest {
  private val medplum = FakeMedplum()

  private fun stored(type: String) =
    medplum.stored.values.filter { it["resourceType"]?.jsonPrimitive?.content == type }

  private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

  @Test
  fun theResetPrintsACardForEachOfSomsaksBirthDoses() = runTest {
    medplum.admin.seedBase(ig)
    medplum.admin.seedJ1(ig, days = 322)

    val hospitalNumber =
      stored("Patient").single().identifiers().single { it.startsWith("$HOSPITAL_NUMBER_SYSTEM|") }
    val doses =
      stored("Immunization").associate {
        it.text("id") to
          it
            .getValue("vaccineCode")
            .jsonObject
            .getValue("coding")
            .jsonArray
            .map { coding -> coding.jsonObject }
            .single { coding -> coding.text("system") == PREQUAL }
            .text("code")
      }
    val cards =
      stored("DocumentReference").associate { card ->
        val attachment =
          card.getValue("content").jsonArray.single().jsonObject.getValue("attachment").jsonObject
        assertEquals("current", card.text("status"))
        assertEquals(true, hospitalNumber in card.identifiers())
        attachment.text("title").removeSuffix(".hc1") to
          CardSigner.product(String(Base64.getDecoder().decode(attachment.text("data"))))
      }

    assertEquals(2, doses.size)
    assertEquals(doses, cards)
  }
}
