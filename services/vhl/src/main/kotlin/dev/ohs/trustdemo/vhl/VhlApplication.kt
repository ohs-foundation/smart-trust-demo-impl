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

import com.auth0.jwt.interfaces.Claim
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val AUTH = "keycloak"
private val FHIR_JSON = ContentType.parse("application/fhir+json")
private val PATIENT_REFERENCE = Regex("""Patient/([A-Za-z0-9\-.]{1,64})""")

/** The caller's Patient id from the token's `fhirUser` claim, or null when it names none. */
private fun fhirUser(claim: Claim): String? =
  claim.asString()?.let { PATIENT_REFERENCE.matchEntire(it)?.groupValues?.get(1) }

fun Application.vhlModule(deps: VhlDeps) {
  val service = VhlService(deps)
  install(Authentication) {
    jwt(AUTH) {
      verifier(deps.jwks, deps.issuer)
      validate { credential ->
        fhirUser(credential.payload.getClaim("fhirUser"))?.let { JWTPrincipal(credential.payload) }
      }
    }
  }
  routing {
    get("/vhl/health") { call.respondText("ok") }
    authenticate(AUTH) {
      post("/vhl/\$generate-vhl") {
        val caller =
          fhirUser(checkNotNull(call.principal<JWTPrincipal>()).payload.getClaim("fhirUser"))!!
        val bearer = call.request.header(HttpHeaders.Authorization).orEmpty().substringAfter(' ')
        try {
          val request = ShareRequest.parse(call.receiveText())
          call.respondText(service.generate(caller, bearer, request).toString(), FHIR_JSON)
        } catch (e: BadRequest) {
          call.respondOutcome(HttpStatusCode.BadRequest, "invalid", e)
        } catch (e: NotYourHousehold) {
          call.respondOutcome(HttpStatusCode.Forbidden, "forbidden", e)
        } catch (e: BackendRefused) {
          val denied =
            e.status == HttpStatusCode.Forbidden || e.status == HttpStatusCode.Unauthorized
          call.respondOutcome(
            if (denied) HttpStatusCode.Forbidden else HttpStatusCode.BadGateway,
            if (denied) "forbidden" else "transient",
            e,
          )
        }
      }
    }
  }
}

private suspend fun ApplicationCall.respondOutcome(
  status: HttpStatusCode,
  code: String,
  cause: Exception,
) {
  val outcome: JsonObject = buildJsonObject {
    put("resourceType", "OperationOutcome")
    putJsonArray("issue") {
      add(
        buildJsonObject {
          put("severity", "error")
          put("code", code)
          put("diagnostics", cause.message.orEmpty())
        }
      )
    }
  }
  respondText(outcome.toString(), FHIR_JSON, status)
}
