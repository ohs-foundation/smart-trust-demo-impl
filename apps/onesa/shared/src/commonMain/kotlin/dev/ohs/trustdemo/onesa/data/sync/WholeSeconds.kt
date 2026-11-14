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
package dev.ohs.trustdemo.onesa.data.sync

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.util.FhirJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drops the fraction from every dateTime and instant a downloaded record carries, outside `meta`.
 * The FHIR engine cannot index a dateTime with fractional seconds, which servers write (audit
 * `recorded`, consent `dateTime`), and fails the whole download; `meta` keeps its own, which the
 * engine reads as an instant and the sync's timestamps rely on.
 */
object WholeSeconds {
  private val fractional =
    Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\.\d+(Z|[+-]\d{2}:\d{2})$""")

  fun trim(resource: Resource): Resource {
    val json = FhirJson.instance.encodeToJsonElement(Resource.serializer(), resource)
    return FhirJson.instance.decodeFromJsonElement(Resource.serializer(), json.trimmed())
  }

  private fun JsonElement.trimmed(): JsonElement =
    when (this) {
      is JsonObject ->
        JsonObject(mapValues { (key, value) -> if (key == "meta") value else value.trimmed() })
      is JsonArray -> JsonArray(map { it.trimmed() })
      is JsonPrimitive ->
        if (isString)
          fractional.matchEntire(content)?.let {
            JsonPrimitive(it.groupValues[1] + it.groupValues[2])
          } ?: this
        else this
    }
}
