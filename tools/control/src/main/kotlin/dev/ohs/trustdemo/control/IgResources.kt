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

import dev.ohs.fhir.model.r4.Resource
import kotlinx.serialization.json.Json

/**
 * The IG's example resources bundled on the classpath, listed by the generated `ig-index.txt`.
 * ActorDefinition and the ImplementationGuide use R5 content the R4 model cannot read, so they are
 * not loaded.
 */
object IgResources {
  private const val INDEX = "ig-index.txt"
  private val R5_ONLY = listOf("ActorDefinition-", "ImplementationGuide-")

  fun all(): List<Resource> {
    val loader = checkNotNull(IgResources::class.java.classLoader)
    val names =
      readText(loader, INDEX).lines().filter { it.isNotBlank() && R5_ONLY.none(it::startsWith) }
    return names.map { Json.decodeFromString(Resource.serializer(), readText(loader, it)) }
  }

  private fun readText(loader: ClassLoader, name: String): String =
    checkNotNull(loader.getResourceAsStream(name)) { "missing classpath resource $name" }
      .use { it.reader().readText() }
}
