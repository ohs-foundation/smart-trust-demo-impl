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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A just-enough Keycloak: the token endpoint, user search and user creation, recorded. */
private class FakeKeycloak(existing: List<JsonObject> = emptyList()) {
  val users = existing.toMutableList()
  val forms = mutableListOf<String>()

  private val engine = MockEngine { request ->
    val path = request.url.encodedPath
    val json = headersOf(HttpHeaders.ContentType, "application/json")
    when {
      path == "/realms/onesa/protocol/openid-connect/token" -> {
        forms += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        respond("""{"access_token":"fake"}""", headers = json)
      }
      request.method == HttpMethod.Get && path == "/admin/realms/onesa/users" -> {
        val name = request.url.parameters["username"]
        val linked = request.url.parameters["q"]?.substringAfter("fhirUser:")
        val found = users.filter { it.text("username") == name || it.fhirUser() == linked }
        respond(JsonArray(found).toString(), headers = json)
      }
      request.method == HttpMethod.Post && path == "/admin/realms/onesa/users" -> {
        users += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        respond("", HttpStatusCode.Created)
      }
      else -> error("unexpected ${request.method} ${request.url}")
    }
  }

  val admin = KeycloakAdmin("http://keycloak.test", "onesa", "trust-demo-cli", "secret", engine)

  private fun JsonObject.fhirUser() =
    this["attributes"]?.jsonObject?.get("fhirUser")?.jsonArray?.single()?.jsonPrimitive?.content
}

class FamilyTest {
  private val hapi =
    FakeStoryHapi(
      listOf(
        parent("Mother", "Nina", "Kimani", "RpMother"),
        parent("Father", "Juma", "Kimani", "RpFather"),
        buildJsonObject {
          put("resourceType", "Patient")
          put("id", "Unlinked")
        },
        buildJsonObject {
          put("resourceType", "Group")
          put("id", "House")
          put("type", "person")
          put("actual", true)
          put("managingEntity", buildJsonObject { put("reference", "RelatedPerson/RpMother") })
          put(
            "member",
            buildJsonArray {
              listOf("Mother", "Father", "Kid").forEach {
                add(
                  buildJsonObject {
                    put("entity", buildJsonObject { put("reference", "Patient/$it") })
                  }
                )
              }
            },
          )
        },
      )
    )

  @Test
  fun aLinkedFamilyLoginIsCreatedWithOneTimePassword() = runTest {
    val keycloak = FakeKeycloak()
    val login = Families(hapi.server, keycloak.admin) { "one-time-pw" }.add("Mother", "nina")

    assertEquals(FamilyLogin("nina", "one-time-pw", "Patient/Mother", "Group/House"), login)
    val user = keycloak.users.single()
    assertEquals(
      "Patient/Mother",
      user
        .getValue("attributes")
        .jsonObject
        .getValue("fhirUser")
        .jsonArray
        .single()
        .jsonPrimitive
        .content,
    )
    assertEquals(
      listOf("/Trust demo family"),
      user.getValue("groups").jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(
      listOf("UPDATE_PASSWORD"),
      user.getValue("requiredActions").jsonArray.map { it.jsonPrimitive.content },
    )
    val password = user.getValue("credentials").jsonArray.single().jsonObject
    assertEquals("password", password.text("type"))
    assertEquals("one-time-pw", password.text("value"))
    assertEquals("true", password.text("temporary"))
    assertEquals("Nina Kimani", "${user.text("firstName")} ${user.text("lastName")}")
    assertEquals("true", user.text("enabled"))
    assertContains(keycloak.forms.single(), "grant_type=client_credentials")
    assertContains(keycloak.forms.single(), "client_id=trust-demo-cli")
  }

  @Test
  fun aPatientNotOnOnesaIsRefusedAndNothingIsWritten() = runTest {
    val keycloak = FakeKeycloak()
    val refusal =
      assertFailsWith<CliktError> { Families(hapi.server, keycloak.admin).add("Nobody", "nina") }
    assertContains(refusal.message.orEmpty(), "Patient/Nobody is not on Onesa")
    assertTrue(keycloak.users.isEmpty())
  }

  @Test
  fun aUsernameThatExistsIsRefused() = runTest {
    val keycloak = FakeKeycloak(listOf(buildJsonObject { put("username", "nina") }))
    val refusal =
      assertFailsWith<CliktError> { Families(hapi.server, keycloak.admin).add("Mother", "nina") }
    assertContains(refusal.message.orEmpty(), "already has a user named nina")
    assertEquals(1, keycloak.users.size)
  }

  @Test
  fun aParentWhoAlreadySignsInIsRefused() = runTest {
    val keycloak = FakeKeycloak()
    Families(hapi.server, keycloak.admin).add("Mother", "nina")
    val refusal =
      assertFailsWith<CliktError> { Families(hapi.server, keycloak.admin).add("Mother", "nina2") }
    assertContains(refusal.message.orEmpty(), "already signs in as nina")
  }

  @Test
  fun aParentWithNoRelatedPersonLinkIsToldToRegisterTheNewbornFirst() = runTest {
    val refusal =
      assertFailsWith<CliktError> {
        Families(hapi.server, FakeKeycloak().admin).add("Unlinked", "x")
      }
    assertContains(refusal.message.orEmpty(), "register a newborn in J2")
  }

  @Test
  fun aParentWhoIsNotTheHouseholdsManagerIsToldWhoIs() = runTest {
    val keycloak = FakeKeycloak()
    val refusal =
      assertFailsWith<CliktError> { Families(hapi.server, keycloak.admin).add("Father", "juma") }
    assertContains(refusal.message.orEmpty(), "its manager is RelatedPerson/RpMother")
    assertContains(refusal.message.orEmpty(), "as the informant")
    assertTrue(keycloak.users.isEmpty())
  }

  private fun parent(id: String, given: String, family: String, related: String) = buildJsonObject {
    put("resourceType", "Patient")
    put("id", id)
    put(
      "name",
      buildJsonArray {
        add(
          buildJsonObject {
            put("family", family)
            put("given", buildJsonArray { add(JsonPrimitive(given)) })
          }
        )
      },
    )
    put(
      "link",
      buildJsonArray {
        add(
          buildJsonObject {
            put("other", buildJsonObject { put("reference", "RelatedPerson/$related") })
            put("type", "seealso")
          }
        )
      },
    )
  }
}
