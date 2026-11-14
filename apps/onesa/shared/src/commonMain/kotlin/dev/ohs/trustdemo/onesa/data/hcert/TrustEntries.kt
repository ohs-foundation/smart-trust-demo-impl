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
package dev.ohs.trustdemo.onesa.data.hcert

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One key of a GDHCN DID trust list: its trust [domain], [participant] country, key [usage] (`DSC`
 * signs documents, `SCA` and `CSCA` are signing authorities) and [kid] in lower-case hex, as a
 * verified card's signer reports it.
 */
data class TrustEntry(
  val domain: String?,
  val participant: String?,
  val usage: String?,
  val kid: String?,
) {
  val signsDocuments: Boolean
    get() = usage == "DSC"

  /** Whether the entry belongs to one of the demo's own trust domains, which only test keys use. */
  val testOnly: Boolean
    get() = TrustDomain.entries.any { it.code == domain }
}

/** The keys of a DID trust list document, from each verification method's id and JWK. */
fun trustEntries(document: String): List<TrustEntry> =
  Json.parseToJsonElement(document)
    .jsonObject["verificationMethod"]
    ?.jsonArray
    .orEmpty()
    .map { it.jsonObject }
    .map { method ->
      val path = method.text("id")?.substringBefore('#')?.split(':').orEmpty()
      val at = path.indexOf("trustlist")
      fun part(offset: Int) = path.getOrNull(at + offset)?.takeIf { at >= 0 && it != "-" }
      TrustEntry(
        domain = part(1),
        participant = part(2),
        usage = part(3),
        kid = method["publicKeyJwk"]?.jsonObject?.text("kid")?.let(::hexKid),
      )
    }

@OptIn(ExperimentalEncodingApi::class)
private fun hexKid(kid: String): String? =
  runCatching { Base64.decode(kid).joinToString("") { (it.toInt() and 0xff).toHexByte() } }
    .getOrNull()

private fun Int.toHexByte(): String = toString(16).padStart(2, '0')

private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
