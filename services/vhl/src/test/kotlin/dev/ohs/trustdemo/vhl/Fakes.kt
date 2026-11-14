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
package dev.ohs.trustdemo.vhl

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val ISSUER = "http://keycloak.onesa.test:8480/realms/ohs-player"
const val PUBLIC_URL = "https://vhl.onesa.test:8443"
private const val KEY_ID = "test-key"

/** A fresh RSA key pair published as a JWKS, and tokens signed with it. */
class TestIdentityProvider(private val pair: KeyPair = newKeyPair()) {
  private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

  val jwksJson: String =
    buildJsonObject {
        putJsonArray("keys") {
          add(
            buildJsonObject {
              val key = pair.public as RSAPublicKey
              put("kid", KEY_ID)
              put("kty", "RSA")
              put("alg", "RS256")
              put("use", "sig")
              put(
                "n",
                urlEncoder.encodeToString(
                  key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
                ),
              )
              put("e", urlEncoder.encodeToString(key.publicExponent.toByteArray()))
            }
          )
        }
      }
      .toString()

  val provider = JwkProvider { keyId ->
    val keys = Json.parseToJsonElement(jwksJson).jsonObject.getValue("keys")
    val key =
      keys.jsonArray
        .map { it.jsonObject }
        .first { it.getValue("kid").jsonPrimitive.content == keyId }
    Jwk.fromValues(key.mapValues { it.value.jsonPrimitive.content })
  }

  fun token(
    fhirUser: String?,
    issuer: String = ISSUER,
    expiresAt: Instant = Instant.now().plusSeconds(3600),
    signedWith: KeyPair = pair,
  ): String =
    JWT.create()
      .withKeyId(KEY_ID)
      .withIssuer(issuer)
      .withSubject("subject")
      .withExpiresAt(Date.from(expiresAt))
      .apply { fhirUser?.let { withClaim("fhirUser", it) } }
      .sign(Algorithm.RSA256(null, signedWith.private as RSAPrivateKey))

  companion object {
    fun newKeyPair(): KeyPair =
      KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
  }
}

fun searchset(vararg resources: JsonObject): JsonObject = buildJsonObject {
  put("resourceType", "Bundle")
  put("type", "searchset")
  put(
    "entry",
    buildJsonArray { resources.forEach { add(buildJsonObject { put("resource", it) }) } },
  )
}

fun patient(id: String, family: String, given: String): JsonObject = buildJsonObject {
  put("resourceType", "Patient")
  put("id", id)
  putJsonArray("identifier") {
    add(
      buildJsonObject {
        put("system", "http://smart.who.int/trust-demo/sid/onesa-national-id")
        put("value", "ID-$id")
      }
    )
  }
  putJsonArray("name") {
    add(
      buildJsonObject {
        put("family", family)
        putJsonArray("given") { add(JsonPrimitive(given)) }
      }
    )
  }
}

fun immunization(id: String, patientId: String): JsonObject = buildJsonObject {
  put("resourceType", "Immunization")
  put("id", id)
  put("status", "completed")
  putJsonObject("patient") { put("reference", "Patient/$patientId") }
}

fun group(vararg memberIds: String): JsonObject = buildJsonObject {
  put("resourceType", "Group")
  put("id", "household")
  putJsonArray("member") {
    memberIds.forEach { id ->
      add(buildJsonObject { putJsonObject("entity") { put("reference", "Patient/$id") } })
    }
  }
}

/** The gateway as a household member sees it; records every request it receives. */
class FakeGateway(
  private val groups: Map<String, JsonObject>,
  private val patients: List<JsonObject>,
  private val immunizations: List<JsonObject> = emptyList(),
) {
  val requests = mutableListOf<RecordedRequest>()

  val engine = MockEngine { request ->
    val path = request.url.encodedPath
    requests += RecordedRequest(request.url.toString(), request.headers[HttpHeaders.Authorization])
    val reply: JsonObject? =
      when {
        path == "/fhir/Group" ->
          searchset(
            *groups
              .filterKeys { "Patient/$it" == request.url.parameters["member"] }
              .values
              .toTypedArray()
          )
        path == "/fhir/Immunization" ->
          searchset(
            *immunizations
              .filter {
                it.getValue("patient").jsonObject.getValue("reference").jsonPrimitive.content ==
                  "Patient/${request.url.parameters["patient"]}"
              }
              .toTypedArray()
          )
        path.startsWith("/fhir/Patient/") ->
          patients.firstOrNull {
            it.getValue("id").jsonPrimitive.content == path.substringAfterLast('/')
          }
        else -> null
      }
    if (reply == null) respond("", HttpStatusCode.NotFound) else respondFhir(reply)
  }
}

data class RecordedRequest(val url: String, val authorization: String?)

/** HAPI as the trusted backend: accepts every transaction and keeps what it was sent. */
class FakeHapi {
  val transactions = mutableListOf<JsonObject>()

  val engine = MockEngine { request ->
    check(request.method == HttpMethod.Post && request.url.encodedPath == "/fhir") {
      "unexpected ${request.method} ${request.url}"
    }
    transactions += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
    respondFhir(
      buildJsonObject {
        put("resourceType", "Bundle")
        put("type", "transaction-response")
      }
    )
  }
}

private fun MockRequestHandleScope.respondFhir(body: JsonObject) =
  respond(
    body.toString(),
    HttpStatusCode.OK,
    headersOf(HttpHeaders.ContentType, "application/fhir+json"),
  )

fun JsonObject.resources(): List<JsonObject> =
  getValue("entry").jsonArray.map { it.jsonObject.getValue("resource").jsonObject }

fun JsonObject.type(): String = getValue("resourceType").jsonPrimitive.content

fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

fun JsonArray.objects(): List<JsonObject> = map { it.jsonObject }
