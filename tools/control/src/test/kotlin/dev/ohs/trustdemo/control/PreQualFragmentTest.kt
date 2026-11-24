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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PREQUAL = "http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualProductIds"

class PreQualFragmentTest {
  private val medplum = FakeMedplum()

  private fun igJson(name: String) =
    Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)).use { it.reader().readText() }
      )
      .jsonObject

  @Test
  fun medplumGetsThePreQualProductsTheIgValueSetLists() = runTest {
    medplum.admin.seedBase(ig)

    val listed =
      igJson("ValueSet-TrustDemoVaccineProducts.json")
        .getValue("compose")
        .jsonObject
        .getValue("include")
        .jsonArray
        .map { it.jsonObject }
        .single { it.getValue("system").jsonPrimitive.content == PREQUAL }
        .getValue("concept")
        .jsonArray
        .map { it.jsonObject.getValue("code").jsonPrimitive.content }
    val fragment =
      medplum.stored.values.single {
        it["resourceType"]?.jsonPrimitive?.content == "CodeSystem" &&
          it["url"]?.jsonPrimitive?.content == PREQUAL
      }

    assertEquals("fragment", fragment.getValue("content").jsonPrimitive.content)
    assertEquals(
      listed,
      fragment.getValue("concept").jsonArray.map {
        it.jsonObject.getValue("code").jsonPrimitive.content
      },
    )
    assertEquals(6, listed.size)
  }
}
