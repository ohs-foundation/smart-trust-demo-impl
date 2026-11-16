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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The copy of a downloaded record this phone stores. The FHIR engine cannot index a dateTime with
 * fractional seconds (see [WholeSeconds]), nor a Consent's `sourceAttachment`, which it passes to
 * its reference indexer and fails the whole download on; the phone never uploads Consents or reads
 * the attachment, so the copy leaves it out.
 */
object IndexableCopy {
  fun of(resource: Resource): Resource {
    val json =
      FhirJson.instance
        .encodeToJsonElement(Resource.serializer(), WholeSeconds.trim(resource))
        .jsonObject
    val kept: JsonObject =
      if (json["resourceType"]?.jsonPrimitive?.content == "Consent")
        JsonObject(json - "sourceAttachment")
      else json
    return FhirJson.instance.decodeFromJsonElement(Resource.serializer(), kept)
  }
}
