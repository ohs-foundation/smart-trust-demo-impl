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
package dev.ohs.trustdemo.onesa.data.verification

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.util.FhirJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The synced copy of the national `ProductToOnesaEpi` map, and what it says. */
internal class NationalMap(private val repository: FhirRepository) {
  private val json = FhirJson.instance

  /** The id the gateway holds the map under, or null before it has synced. */
  suspend fun id(): String? =
    repository
      .all("ConceptMap")
      .map { it.toJson() }
      .firstOrNull { it.text("url") == ScanTransactions.EPI_MAP }
      ?.text("id")

  suspend fun epiCodes(): Map<String, String> =
    repository
      .all("ConceptMap")
      .map { it.toJson() }
      .filter { it.text("url") == ScanTransactions.EPI_MAP }
      .map(::epiCodesOf)
      .fold(emptyMap()) { all, codes -> all + codes }

  private fun Resource.toJson(): JsonObject =
    json.encodeToJsonElement(Resource.serializer(), this).jsonObject
}

/** The schedule code each product maps to in [map]. */
internal fun epiCodesOf(map: JsonObject): Map<String, String> =
  map["group"]
    ?.jsonArray
    .orEmpty()
    .map { it.jsonObject }
    .filter {
      it.text("source") == ScanTransactions.PRODUCT_SYSTEM &&
        it.text("target") == ScanTransactions.EPI_SYSTEM
    }
    .flatMap { it["element"]?.jsonArray.orEmpty() }
    .mapNotNull { element ->
      val product = element.jsonObject.text("code")
      val target = element.jsonObject["target"]?.jsonArray?.firstOrNull()?.jsonObject?.text("code")
      if (product != null && target != null) product to target else null
    }
    .toMap()

internal fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.codings(): List<JsonObject> =
  this["vaccineCode"]?.jsonObject?.get("coding")?.jsonArray.orEmpty().map { it.jsonObject }
