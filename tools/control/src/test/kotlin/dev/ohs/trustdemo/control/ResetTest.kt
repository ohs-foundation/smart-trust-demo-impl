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

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Resource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class FakeHapi {
  val stored = mutableMapOf<String, Resource>()
  var expunges = 0

  private val engine = MockEngine { request ->
    val path = request.url.encodedPath.removePrefix("/fhir")
    val body = (request.body as TextContent).text
    when {
      request.method == HttpMethod.Post && path == "/\$expunge" -> {
        expunges++
        stored.clear()
        respond("{}", headers = fhirJson)
      }
      request.method == HttpMethod.Post && path == "" -> {
        val bundle = Json.decodeFromString(Resource.serializer(), body) as Bundle
        bundle.entry.forEach { entry ->
          val resource = checkNotNull(entry.resource)
          stored["${resource.resourceType()}/${resource.id}"] = resource
        }
        respond(body, headers = fhirJson)
      }
      else -> error("unexpected ${request.method} ${request.url}")
    }
  }

  val server = FhirServer("http://hapi.test/fhir", { null }, engine)

  private companion object {
    val fhirJson = headersOf(HttpHeaders.ContentType, "application/fhir+json")
  }
}

class ResetTest {
  private val today = LocalDate.of(2026, 11, 14)

  @Test
  fun resetTwiceLeavesOnlyBaseData() = runTest {
    val hapi = FakeHapi()
    Reset(hapi.server).run(today)
    Reset(hapi.server).run(today)
    assertEquals(2, hapi.expunges)
    assertEquals(Seed.onesaBase(ig).entry.size, hapi.stored.size)
  }
}
