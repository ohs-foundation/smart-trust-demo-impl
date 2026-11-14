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
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private const val AUTH = "keycloak"
private const val MAX_KEY = 255
internal val FHIR_JSON = ContentType.parse("application/fhir+json")
private val PATIENT_REFERENCE = Regex("""Patient/([A-Za-z0-9][A-Za-z0-9\-.]{0,63})""")

/** The caller's Patient id from the token's `fhirUser` claim, or null when it names none. */
private fun fhirUser(claim: Claim): String? =
  claim.asString()?.let { PATIENT_REFERENCE.matchEntire(it)?.groupValues?.get(1) }

fun Application.vhlModule(deps: VhlDeps) {
  val service = VhlService(deps)
  val shares = IdempotencyStore(deps.clock)
  install(Authentication) {
    jwt(AUTH) {
      verifier(deps.jwks, deps.issuer)
      validate { credential ->
        val fromAllowedClient = credential.payload.getClaim("azp").asString() in deps.allowedClients
        fhirUser(credential.payload.getClaim("fhirUser"))
          ?.takeIf { fromAllowedClient }
          ?.let { JWTPrincipal(credential.payload) }
      }
    }
  }
  routing {
    get("/vhl/health") { call.respondText("ok") }
    signedRoutes(deps)
    authenticate(AUTH) {
      post("/vhl/\$generate-vhl") {
        val caller =
          fhirUser(checkNotNull(call.principal<JWTPrincipal>()).payload.getClaim("fhirUser"))!!
        val bearer = call.request.header(HttpHeaders.Authorization).orEmpty().substringAfter(' ')
        try {
          val body = call.receiveText()
          val key = call.request.header("Idempotency-Key")
          if (key != null && key.length !in 1..MAX_KEY) {
            throw BadRequest("the Idempotency-Key is 1 to $MAX_KEY characters")
          }
          suspend fun generate() =
            service.generate(caller, bearer, ShareRequest.parse(body)).toString()
          call.respondText(
            if (key == null) generate() else shares.once(caller, key, body, ::generate),
            FHIR_JSON,
          )
        } catch (e: BadRequest) {
          call.respondOutcome(HttpStatusCode.BadRequest, "invalid", e)
        } catch (e: KeyReused) {
          call.respondOutcome(HttpStatusCode.UnprocessableEntity, "processing", e)
        } catch (e: NotYourHousehold) {
          call.respondOutcome(HttpStatusCode.Forbidden, "forbidden", e)
        } catch (e: BackendRefused) {
          if (e.deniedCaller) call.respondOutcome(HttpStatusCode.Forbidden, "forbidden", e)
          else call.respondFailure(HttpStatusCode.BadGateway, e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          call.respondFailure(HttpStatusCode.InternalServerError, e)
        }
      }
      post("/vhl/\$withdraw") {
        val caller =
          fhirUser(checkNotNull(call.principal<JWTPrincipal>()).payload.getClaim("fhirUser"))!!
        try {
          service.withdraw(caller, consentOf(call.receiveText()))
          call.respondOutcome(
            HttpStatusCode.OK,
            "informational",
            Exception("withdrawn"),
            "information",
          )
        } catch (e: BadRequest) {
          call.respondOutcome(HttpStatusCode.BadRequest, "invalid", e)
        } catch (e: NotYourConsent) {
          call.respondOutcome(HttpStatusCode.Forbidden, "forbidden", e)
        } catch (e: NoSuchConsent) {
          call.respondOutcome(HttpStatusCode.NotFound, "not-found", e)
        } catch (e: WithdrawConflict) {
          call.respondOutcome(HttpStatusCode.Conflict, "conflict", e)
        } catch (e: BackendRefused) {
          call.respondFailure(HttpStatusCode.BadGateway, e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          call.respondFailure(HttpStatusCode.InternalServerError, e)
        }
      }
    }
  }
}

private val log = LoggerFactory.getLogger("dev.ohs.trustdemo.vhl")

/** Logs only what the failure was and where; nothing of the request itself. */
internal suspend fun ApplicationCall.respondFailure(status: HttpStatusCode, cause: Exception) {
  log.error("{} on {}", cause::class.simpleName, request.path())
  respondOutcome(status, "exception", Exception("the service could not complete the request"))
}

internal suspend fun ApplicationCall.respondOutcome(
  status: HttpStatusCode,
  code: String,
  cause: Exception,
  severity: String = "error",
  reason: Refusal? = null,
) {
  val outcome: JsonObject = buildJsonObject {
    put("resourceType", "OperationOutcome")
    putJsonArray("issue") {
      add(
        buildJsonObject {
          put("severity", severity)
          put("code", code)
          put("diagnostics", cause.message.orEmpty())
          reason?.let {
            putJsonObject("details") {
              putJsonArray("coding") {
                add(
                  buildJsonObject {
                    put("system", it.system)
                    put("code", it.code)
                  }
                )
              }
            }
          }
        }
      )
    }
  }
  respondText(outcome.toString(), FHIR_JSON, status)
}
