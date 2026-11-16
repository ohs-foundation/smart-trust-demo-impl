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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val CORE = "http://hl7.org/fhir/StructureDefinition"

private fun definition(name: String, vararg elements: Triple<String, String?, Int>) =
  buildJsonObject {
    put("resourceType", "StructureDefinition")
    put("url", "$CORE/$name")
    put(
      "snapshot",
      buildJsonObject {
        put(
          "element",
          JsonArray(
            (listOf(Triple(name, null, 0)) +
                elements.map { (field, type, min) -> Triple("$name.$field", type, min) })
              .map { (path, type, min) ->
                buildJsonObject {
                  put("id", path)
                  put("path", path)
                  put("min", min)
                  put("max", "*")
                  if (type != null) {
                    put("type", JsonArray(listOf(buildJsonObject { put("code", type) })))
                  }
                }
              }
          ),
        )
      },
    )
  }

/** The core definitions the Taifafeki profiles constrain, cut to the elements they touch. */
val baseDefinitions: List<JsonObject> =
  listOf(
    definition(
      "Patient",
      Triple("identifier", "Identifier", 0),
      Triple("name", "HumanName", 0),
      Triple("gender", "code", 0),
      Triple("birthDate", "date", 0),
    ),
    definition("Identifier", Triple("system", "uri", 0), Triple("value", "string", 0)),
    definition(
      "Immunization",
      Triple("status", "code", 1),
      Triple("vaccineCode", "CodeableConcept", 1),
      Triple("patient", "Reference", 1),
      Triple("occurrence[x]", "dateTime", 1),
      Triple("primarySource", "boolean", 1),
      Triple("lotNumber", "string", 0),
      Triple("performer", "BackboneElement", 0),
      Triple("protocolApplied", "BackboneElement", 0),
    ),
    definition("CodeableConcept", Triple("coding", "Coding", 0), Triple("text", "string", 0)),
    definition("Coding", Triple("system", "uri", 0), Triple("code", "code", 0)),
    definition(
      "Provenance",
      Triple("target", "Reference", 1),
      Triple("recorded", "instant", 1),
      Triple("activity", "CodeableConcept", 0),
      Triple("agent", "BackboneElement", 1),
      Triple("entity", "BackboneElement", 0),
    ),
  )
