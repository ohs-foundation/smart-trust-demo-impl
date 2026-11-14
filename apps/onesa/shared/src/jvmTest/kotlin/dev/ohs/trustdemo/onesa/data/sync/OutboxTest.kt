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

import dev.ohs.fhir.engine.sync.createDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.IOException
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OutboxTest {
  private val posted = mutableListOf<String>()
  private var answer: MockRequestHandleScope.(id: String) -> HttpResponseData = {
    respond("{}", HttpStatusCode.OK)
  }
  private val outbox =
    Outbox(
      dataStore =
        createDataStore {
          createTempFile(suffix = ".preferences_pb").toFile().apply { deleteOnExit() }.absolutePath
        },
      httpClient = HttpClient(MockEngine { request -> handle(request) }),
      baseUrl = "http://gateway/fhir",
      accessToken = { "token" },
    )

  private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
    if (request.method == HttpMethod.Get) return respond("{}", HttpStatusCode.NotFound)
    val id =
      Json.parseToJsonElement((request.body as TextContent).text)
        .jsonObject
        .getValue("id")
        .jsonPrimitive
        .content
    posted += id
    return answer(id)
  }

  private fun entry(id: String) =
    OutboxEntry(
      key = id,
      transaction =
        buildJsonObject {
          put("resourceType", "Bundle")
          put("id", id)
          put("type", "transaction")
        },
      created = "Patient/$id",
    )

  @Test
  fun theOutboxSendsOldestFirstAndClearsOnSuccess() = runTest {
    outbox.add(entry("first"))
    outbox.add(entry("second"))

    outbox.send()

    assertEquals(listOf("first", "second"), posted)
    assertEquals(emptyList(), outbox.entries())
  }

  @Test
  fun aNetworkFailureKeepsTheEntry() = runTest {
    answer = { throw IOException("no signal") }
    outbox.add(entry("first"))
    outbox.add(entry("second"))

    outbox.send()

    assertEquals(listOf("first"), posted)
    assertEquals(listOf(entry("first"), entry("second")), outbox.entries())
  }

  @Test
  fun aRefusalIsKeptWithItsReasonAndNotResent() = runTest {
    answer = { id ->
      if (id == "first")
        respond(
          """{"resourceType":"OperationOutcome","issue":[{"severity":"error",
            "code":"conflict","diagnostics":"Group/house is at version 2"}]}""",
          HttpStatusCode.PreconditionFailed,
        )
      else respond("{}", HttpStatusCode.OK)
    }
    outbox.add(entry("first"))
    outbox.add(entry("second"))

    outbox.send()
    outbox.send()

    assertEquals(listOf("first", "second"), posted)
    assertEquals(
      listOf(entry("first").copy(refused = Refusal(412, "Group/house is at version 2"))),
      outbox.entries(),
    )
  }
}
