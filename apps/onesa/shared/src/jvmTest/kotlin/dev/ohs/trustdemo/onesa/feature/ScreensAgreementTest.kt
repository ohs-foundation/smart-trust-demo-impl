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
package dev.ohs.trustdemo.onesa.feature

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Spec check 13: every renderer the app registers has the ViewDefinition, ViewJoinMap and
 * ViewConfig it reads.
 */
class ScreensAgreementTest {
  private val kotlinRoot = File("src/commonMain/kotlin")
  private val files = File("src/commonMain/composeResources/files")
  private val registry = File(kotlinRoot, "dev/ohs/trustdemo/onesa/AppViewRegistry.kt")
  private val sources = kotlinRoot.walkTopDown().filter { it.extension == "kt" }.toList()
  private val sourceText = sources.joinToString("\n") { it.readText() }

  private val registration =
    Regex("""register(Component|Layout)<(\w+)(?:,\s*(\w+))?>\(\s*([\w.]+)""")
  private val listLayouts = Regex("""\w+ListRenderer\.VIEW_TYPE""")

  private class Registered(
    val kind: String,
    val state: String,
    val config: String?,
    val viewType: String,
  )

  private fun registrars(): List<String> =
    Regex("""(register\w+)\(\)""").findAll(registry.readText()).map { it.groupValues[1] }.toList()

  private fun registered(registrar: String): List<Registered> {
    val declaration = Regex("""fun ViewRegistry\.$registrar\(\)""")
    val body =
      sources
        .map { it.readText() }
        .firstNotNullOfOrNull { text ->
          declaration.find(text)?.let { text.substring(it.range.last) }
        } ?: error("$registrar is called in AppViewRegistry.kt but declared nowhere")
    return registration
      .findAll(body)
      .map {
        Registered(
          it.groupValues[1],
          it.groupValues[2],
          it.groupValues[3].ifEmpty { null },
          it.groupValues[4],
        )
      }
      .toList()
  }

  private fun json(path: String): JsonObject? =
    File(files, path)
      .takeIf { it.isFile }
      ?.let { Json.parseToJsonElement(it.readText()).jsonObject }

  // TrustListState is a hand-written display state of the trust-list status screen, not a FHIR
  // record, so it has no view of FHIR data.
  private fun handWritten(state: String) = Regex("""class $state\(""").containsMatchIn(sourceText)

  @Test
  fun everyRegisteredRendererHasItsViewDefinitionViewJoinMapAndViewConfig() {
    val registrars = registrars()
    val all = registrars.flatMap { registered(it) }
    val components = all.filter { it.kind == "Component" }
    assertTrue(
      registrars.size >= 5,
      "found only ${registrars.size} registration functions in AppViewRegistry.kt",
    )
    assertTrue(components.size >= 12, "found only ${components.size} registered components")

    val problems = mutableListOf<String>()
    all.forEach { renderer ->
      val label = "${renderer.state} as ${renderer.viewType}"
      if (!handWritten(renderer.state)) {
        val joinMap = json("states/Binary-${renderer.state}.json")
        if (joinMap == null) {
          problems += "$label: states/Binary-${renderer.state}.json (ViewJoinMap) is missing"
        } else {
          val type = joinMap["resourceType"]?.jsonPrimitive?.content.orEmpty()
          if (!type.endsWith("/ViewJoinMap"))
            problems += "$label: ${renderer.state} is a $type, not a ViewJoinMap"
          val view = joinMap["view"]?.jsonPrimitive?.content
          val definition = view?.let { json("states/Binary-$it.json") }
          val definitionType = definition?.get("resourceType")?.jsonPrimitive?.content.orEmpty()
          if (!definitionType.endsWith("/ViewDefinition")) {
            problems +=
              "$label: the ViewJoinMap names view $view, which has no ViewDefinition in states/"
          }
        }
      }
      val viewType = renderer.viewType.substringAfter("ViewTypeCS.")
      if (!listLayouts.matches(renderer.viewType)) {
        val config = json("configs/Binary-${viewType}Config.json")
        val named = renderer.config?.let { json("configs/Binary-$it.json") } ?: config
        val declared = named?.get("viewType")?.jsonPrimitive?.content
        if (named == null)
          problems +=
            "$label: configs/Binary-${renderer.config ?: "${viewType}Config"}.json (ViewConfig) is missing"
        else if (declared != viewType)
          problems += "$label: its ViewConfig declares viewType $declared"
      }
    }

    val registeredTypes = all.map { it.viewType.substringAfter("ViewTypeCS.") }.toSet()
    File(files, "configs")
      .listFiles { file -> file.name.startsWith("Binary-") }
      .orEmpty()
      .forEach { file ->
        val declared =
          Json.parseToJsonElement(file.readText()).jsonObject["viewType"]?.jsonPrimitive?.content
        if (declared !in registeredTypes)
          problems += "${file.name}: no renderer is registered for viewType $declared"
      }

    assertEquals(
      emptyList(),
      problems,
      "screens without their views (${components.size} components checked)",
    )
  }
}
