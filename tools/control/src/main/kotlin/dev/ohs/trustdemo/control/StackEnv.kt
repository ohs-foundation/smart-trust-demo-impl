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

import com.github.ajalt.clikt.core.CliktError
import java.nio.file.Files
import java.nio.file.Path

/** The `KEY=value` settings of the stack's `.env` file. */
class StackEnv(private val values: Map<String, String>, private val source: Path? = null) {
  fun require(name: String): String =
    values[name]
      ?: throw CliktError("$source is missing $name — run stack/init-env.sh, or pass --stack-env")

  companion object {
    fun load(path: Path): StackEnv =
      StackEnv(
        Files.readAllLines(path)
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
          .associate { it.substringBefore('=') to it.substringAfter('=').trim('"', '\'') },
        path,
      )
  }
}
