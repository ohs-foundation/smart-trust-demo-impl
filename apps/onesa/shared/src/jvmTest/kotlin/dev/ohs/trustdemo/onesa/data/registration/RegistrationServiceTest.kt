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
package dev.ohs.trustdemo.onesa.data.registration

import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RegistrationServiceTest {
  private val repository = InMemorySampleFhirRepository()
  private val server = FakeHousehold()
  private val service =
    RegistrationService(
      repository = repository,
      scannedCards = ScannedCards(),
      session = { RegistrationSession("token", "vilay", "lomaru-dhc") },
      httpClient = HttpClient(server.engine),
      baseUrl = "http://gateway/fhir",
      refresh = { true },
      clock = { Instant.parse("2026-11-14T03:00:00Z") },
    )

  private val firstChild =
    """{"resourceType":"Patient","id":"somsak","gender":"male","meta":{"versionId":"1"},
      "name":[{"family":"Sengmany","given":["Somsak"]}]}"""

  private val group =
    """{"resourceType":"Group","id":"house","type":"person","actual":true,"quantity":3,
      "meta":{"versionId":"1"},
      "identifier":[{"system":"http://smart.who.int/trust-demo/sid/onesa-household-id","value":"HH-1"}],
      "managingEntity":{"reference":"RelatedPerson/bounmy-rp"},
      "member":[{"entity":{"reference":"Patient/bounmy"}},{"entity":{"reference":"Patient/khamla"}},
                {"entity":{"reference":"Patient/somsak"}}]}"""

  private val bounmy =
    """{"resourceType":"Patient","id":"bounmy","gender":"male","meta":{"versionId":"1"},
      "link":[{"other":{"reference":"RelatedPerson/bounmy-rp"},"type":"seealso"}],
      "name":[{"family":"Sengmany","given":["Bounmy"]}]}"""

  private val khamla =
    """{"resourceType":"Patient","id":"khamla","meta":{"versionId":"1"},
      "name":[{"family":"Sengmany","given":["Khamla"]}]}"""

  private suspend fun synced() {
    listOf(group, bounmy, khamla, firstChild).forEach {
      repository.upsert(FhirJson.instance.decodeFromString(Resource.serializer(), it))
      server.store(it)
    }
  }

  private fun form(given: String, mother: String? = "khamla", father: String? = "bounmy") =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      """{"resourceType":"QuestionnaireResponse","status":"completed","item":[
        {"linkId":"household","answer":[{"valueString":"HH-1"}]},
        {"linkId":"given","answer":[{"valueString":"$given"}]},
        {"linkId":"family","answer":[{"valueString":"Sengmany"}]},
        {"linkId":"sex","answer":[{"valueCoding":{"code":"female"}}]},
        {"linkId":"birthDate","answer":[{"valueDate":"2026-11-14"}]},
        ${parent("mother", mother)}
        ${parent("father", father)}
        {"linkId":"relationship","answer":[{"valueCoding":{"code":"MTH"}}]}]}""",
    ) as QuestionnaireResponse

  private fun parent(linkId: String, id: String?) =
    id?.let { """{"linkId":"$linkId","answer":[{"valueCoding":{"code":"$it"}}]},""" }.orEmpty()

  @Test
  fun aNewbornWithOneParentRegisters() = runTest {
    synced()

    service.register("house", form("Noy", mother = null), key = "one")

    val child =
      server.resources.values.single { it.text("resourceType") == "Patient" && it.isNew() }
    assertEquals(
      listOf("FTH"),
      child.getValue("contact").jsonArray.map { it.jsonObject.roleCode() },
    )
    val related = server.resources.filterKeys { it.startsWith("RelatedPerson/") }
    assertEquals(listOf("FTH"), related.values.map { it.roleCode() })
    assertEquals(
      related.keys.single(),
      server.resources
        .getValue("Group/house")
        .getValue("managingEntity")
        .jsonObject
        .text("reference"),
    )
    assertTrue("Patient/khamla" !in server.attempts.single())
    assertEquals(4, server.resources.getValue("Group/house").getValue("member").jsonArray.size)
  }

  @Test
  fun aNewbornWithNoParentIsRefused() = runTest {
    synced()

    val refusal =
      assertFailsWith<IllegalStateException> {
        service.register("house", form("Noy", mother = null, father = null), key = "none")
      }

    assertEquals("Name at least one parent or guardian: the mother or the father.", refusal.message)
    assertTrue(server.attempts.isEmpty())
  }

  @Test
  fun theFormOffersTheHouseholdAndNamesTheFatherItKnows() = runTest {
    synced()

    val options = service.parentOptions("house").map { it.getValue("valueCoding").jsonObject }
    val answers = service.initialAnswers("house")

    assertEquals(listOf("bounmy", "khamla", "somsak"), options.map { it.text("code") })
    assertEquals("Bounmy Sengmany", options.first().text("display"))
    assertEquals(
      "bounmy",
      answers.getValue("father").getValue("valueCoding").jsonObject.text("code"),
    )
    assertEquals(null, answers["mother"])
  }

  @Test
  fun aSecondNewbornHasOnlyItsParents() = runTest {
    synced()

    service.register("house", form("Noy"), key = "second")

    val child =
      server.resources.values.single { it.text("resourceType") == "Patient" && it.isNew() }
    val contacts = child.getValue("contact").jsonArray.map { it.jsonObject }
    assertEquals(listOf("MTH", "FTH"), contacts.map { it.roleCode() })
    val related = server.resources.filterKeys { it.startsWith("RelatedPerson/") }.values
    assertEquals(listOf("MTH", "FTH"), related.map { it.roleCode() })
    assertTrue("Patient/somsak" !in server.attempts.single())
    assertEquals(4, server.resources.getValue("Group/house").getValue("member").jsonArray.size)
  }

  @Test
  fun aRetriedRegistrationWritesOneChild() = runTest {
    synced()
    server.failNext = HttpStatusCode.InternalServerError

    assertFailsWith<IllegalStateException> { service.register("house", form("Noy"), key = "k1") }
    service.register("house", form("Noy"), key = "k1")
    val committed = server.resources.keys.toSet()

    assertFailsWith<HouseholdChangedException> {
      service.register("house", form("Noy"), key = "k1")
    }

    assertEquals(committed, server.resources.keys.toSet())
    assertEquals(
      1,
      server.resources.values.count { it.isNew() && it.text("resourceType") == "Patient" },
    )
    assertEquals(server.attempts[0], server.attempts[1])
  }

  @Test
  fun aRetryOnAFreshCopyRewritesTheSameChild() = runTest {
    synced()
    server.loseNextReply = true
    assertFailsWith<IOException> { service.register("house", form("Noy"), key = "k4") }
    server.resources.values.forEach {
      repository.upsert(FhirJson.instance.decodeFromJsonElement(Resource.serializer(), it))
    }

    service.register("house", form("Noy"), key = "k4")

    assertEquals(
      1,
      server.resources.values.count { it.isNew() && it.text("resourceType") == "Patient" },
    )
    assertEquals(4, server.resources.getValue("Group/house").getValue("member").jsonArray.size)
    assertEquals(2, server.resources.getValue("Patient/bounmy").getValue("link").jsonArray.size)
  }

  @Test
  fun aLostReplyThenARetryWritesOneChild() = runTest {
    synced()
    server.loseNextReply = true

    assertFailsWith<IOException> { service.register("house", form("Noy"), key = "k2") }
    assertFailsWith<HouseholdChangedException> {
      service.register("house", form("Noy"), key = "k2")
    }

    assertEquals(
      1,
      server.resources.values.count { it.isNew() && it.text("resourceType") == "Patient" },
    )
    assertEquals(server.attempts[0], server.attempts[1])
  }

  @Test
  fun aChangedHouseholdIsNotOverwritten() = runTest {
    synced()
    server.store(group.replace("\"versionId\":\"1\"", "\"versionId\":\"2\""))
    val before = server.resources.toMap()

    assertFailsWith<HouseholdChangedException> {
      service.register("house", form("Noy"), key = "k3")
    }

    assertEquals(before, server.resources)
  }

  private fun JsonObject.text(name: String) = get(name)?.jsonPrimitive?.content

  private fun JsonObject.isNew() = text("id") !in setOf("bounmy", "khamla", "somsak")

  private fun JsonObject.roleCode(): String =
    getValue("relationship")
      .jsonArray
      .single()
      .jsonObject
      .getValue("coding")
      .jsonArray
      .single()
      .jsonObject
      .getValue("code")
      .jsonPrimitive
      .content
}

/**
 * The gateway's household: records with versions, `If-Match` honoured, a transaction all or
 * nothing.
 */
private class FakeHousehold {
  val resources = linkedMapOf<String, JsonObject>()
  val attempts = mutableListOf<List<String>>()
  var failNext: HttpStatusCode? = null
  var loseNextReply = false

  fun store(json: String) {
    val resource = FhirJson.instance.parseToJsonElement(json).jsonObject
    resources[
      "${resource.getValue("resourceType").jsonPrimitive.content}/${resource.getValue("id").jsonPrimitive.content}"] =
      resource
  }

  val engine = MockEngine { request ->
    val entries =
      FhirJson.instance
        .parseToJsonElement((request.body as TextContent).text)
        .jsonObject
        .getValue("entry")
        .jsonArray
        .map { it.jsonObject }
    val urls =
      entries.map { it.getValue("request").jsonObject.getValue("url").jsonPrimitive.content }
    attempts += urls
    val failure = failNext
    if (failure != null) {
      failNext = null
      return@MockEngine respond("{}", failure)
    }
    val moved =
      entries.any { entry ->
        val request = entry.getValue("request").jsonObject
        val expected = request["ifMatch"]?.jsonPrimitive?.content?.removePrefix("W/")?.trim('"')
        expected != null &&
          resources[request.getValue("url").jsonPrimitive.content]?.version() != expected
      }
    if (moved) return@MockEngine respond("{}", HttpStatusCode.PreconditionFailed)
    entries.forEach { entry ->
      val url = entry.getValue("request").jsonObject.getValue("url").jsonPrimitive.content
      val next = ((resources[url]?.version()?.toInt() ?: 0) + 1).toString()
      val body = entry.getValue("resource").jsonObject
      resources[url] = JsonObject(body + ("meta" to buildJsonObject { put("versionId", next) }))
    }
    if (loseNextReply) {
      loseNextReply = false
      throw IOException("the reply was lost")
    }
    respond(
      """{"resourceType":"Bundle","type":"transaction-response"}""",
      HttpStatusCode.OK,
      headersOf(HttpHeaders.ContentType, "application/fhir+json"),
    )
  }

  private fun JsonObject.version(): String? =
    this["meta"]?.jsonObject?.get("versionId")?.jsonPrimitive?.content
}
