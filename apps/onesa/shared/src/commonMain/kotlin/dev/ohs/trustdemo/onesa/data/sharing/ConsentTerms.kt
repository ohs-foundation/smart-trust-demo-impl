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

import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.util.FhirJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** One consent the holder agrees to: what the IG's `TrustDemoVhlConsent` says it means. */
data class ConsentTerm(val code: String, val display: String, val definition: String)

/** The terms of a share, from the IG's CodeSystem bundled with the app. */
object ConsentTerms {
  fun parse(codeSystem: String): List<ConsentTerm> =
    FhirJson.instance
      .parseToJsonElement(codeSystem)
      .jsonObject
      .getValue("concept")
      .jsonArray
      .map { it.jsonObject }
      .map { ConsentTerm(it.text("code"), it.text("display"), it.text("definition")) }

  @OptIn(ExperimentalResourceApi::class)
  suspend fun load(): List<ConsentTerm> =
    parse(Res.readBytes("files/consent/CodeSystem-TrustDemoVhlConsent.json").decodeToString())

  private fun JsonObject.text(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
}
