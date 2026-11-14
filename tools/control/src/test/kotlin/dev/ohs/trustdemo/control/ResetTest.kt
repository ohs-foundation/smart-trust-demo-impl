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
import dev.ohs.fhir.model.r4.Subscription
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class FakeHapi {
  val stored = mutableMapOf<String, Resource>()
  var expunges = 0

  private val engine = MockEngine { request ->
    val path = request.url.encodedPath.removePrefix("/fhir")
    when {
      request.method == HttpMethod.Get && path == "/metadata" -> respond("{}", headers = fhirJson)
      request.method == HttpMethod.Post && path == "/\$expunge" -> {
        expunges++
        stored.clear()
        respond("{}", headers = fhirJson)
      }
      request.method == HttpMethod.Post && path == "" -> {
        val body = (request.body as TextContent).text
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

private const val WATCH_PORT = 18090
private const val CLIENT_ORIGIN = "http://client.test:8480"

class ResetTest {
  private val today = LocalDate.of(2026, 11, 14)

  @Test
  fun resetTwiceLeavesOnlyBaseData() = runTest {
    val hapi = FakeHapi()
    val medplum = FakeMedplum()
    val secrets = createTempDirectory()
    repeat(2) {
      Reset(hapi.server, medplum.admin, "password", secrets, WATCH_PORT, CLIENT_ORIGIN).run(today)
    }
    assertEquals(2, hapi.expunges)
    assertEquals(Seed.onesaBase(ig).entry.size + 1, hapi.stored.size)
    assertEquals(2, medplum.stored.values.count { it.hasIdentifier("CRH-HN-68-104377") })
    assertEquals(1, medplum.invites)
    assertEquals(
      medplum.stored.keys.single { it.startsWith("ClientApplication/") }.substringAfter('/'),
      secrets.resolve("medplum-client-id").readText(),
    )
    assertEquals(
      setOf("clinic-viewer.crt", "clinic-viewer.key", "medplum-client-id"),
      secrets.listDirectoryEntries().map { it.name }.toSet(),
    )
  }

  @Test
  fun resetRegistersOneSubscriptionWithNoPayload() = runTest {
    val hapi = FakeHapi()
    repeat(2) {
      Reset(
          hapi.server,
          FakeMedplum().admin,
          "password",
          createTempDirectory(),
          WATCH_PORT,
          CLIENT_ORIGIN,
        )
        .run(today)
    }
    val subscription = hapi.stored.values.filterIsInstance<Subscription>().single()
    assertEquals("AuditEvent?", subscription.criteria.value)
    assertEquals(
      "http://host.docker.internal:$WATCH_PORT/hook",
      subscription.channel.endpoint?.value,
    )
    assertNull(subscription.channel.payload)
  }
}
