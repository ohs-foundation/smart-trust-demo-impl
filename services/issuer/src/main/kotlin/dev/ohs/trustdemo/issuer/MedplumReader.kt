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
package dev.ohs.trustdemo.issuer

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/** Medplum, read with the caller's own token so its access policy decides what the caller sees. */
class MedplumReader(private val baseUrl: String, engine: HttpClientEngine) {
  private val client = HttpClient(engine)

  suspend fun read(path: String, bearer: String): String {
    val response =
      client.get("$baseUrl/$path") {
        header(HttpHeaders.Accept, "application/fhir+json")
        header(HttpHeaders.Authorization, "Bearer $bearer")
      }
    if (!response.status.isSuccess()) throw MedplumRefused(response.status.value)
    return response.bodyAsText()
  }
}

/** Medplum answered [status]: the caller may not read the record, or there is none. */
class MedplumRefused(val status: Int) : Exception("Medplum answered $status")
