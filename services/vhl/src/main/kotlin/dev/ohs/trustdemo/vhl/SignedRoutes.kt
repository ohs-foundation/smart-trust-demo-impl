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

import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

private const val MAX_BODY_BYTES = 16L * 1024
private val ID = Regex("""[A-Za-z0-9\-.]{1,64}""")

/**
 * ITI-YY5 at the path hcert-core's `buildManifestRequest` posts to, and ITI-68 at the document URLs
 * the manifest lists. Both answer only clinics whose signed request verifies and the release
 * decision allows.
 */
internal fun Route.signedRoutes(deps: VhlDeps) {
  val verifier = SignedRequestVerifier(URI(deps.publicUrl).authority, deps.clinicAnchor, deps.clock)

  post("/vhl/List/_search") {
    val body = call.receiveChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
    if (body.size > MAX_BODY_BYTES) {
      call.respondOutcome(HttpStatusCode.PayloadTooLarge, "too-costly", Exception("body too large"))
      return@post
    }
    val requester = call.verified(verifier, body) ?: return@post
    val form = parseQueryString(body.decodeToString())
    val listId = form["_id"]?.takeIf { ID.matches(it) && form.getAll("_id")?.size == 1 }
    val recipient = form["recipient"]?.takeIf { it.isNotBlank() }
    if (listId == null || recipient == null) {
      call.respondOutcome(HttpStatusCode.BadRequest, "invalid", Exception("not a manifest search"))
      return@post
    }
    call.decide(deps, ReleaseRequest.Manifest(requester, listId, recipient, form["passcode"]))
  }

  get("/vhl/Binary/{id}") {
    val requester = call.verified(verifier, null) ?: return@get
    val id = call.parameters["id"]?.takeIf { ID.matches(it) }
    if (id == null) {
      call.respondOutcome(HttpStatusCode.NotFound, "not-found", Exception("no such document"))
      return@get
    }
    call.decide(deps, ReleaseRequest.Document(requester, id))
  }
}

private suspend fun ApplicationCall.verified(
  verifier: SignedRequestVerifier,
  body: ByteArray?,
): Requester? =
  try {
    verifier.verify(
      ReceivedRequest(request.httpMethod.value, request.uri, body) { request.header(it) }
    )
  } catch (e: RequestRefused) {
    respondOutcome(
      e.status,
      if (e.status == HttpStatusCode.Unauthorized) "security" else "transient",
      e,
    )
    null
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    respondFailure(HttpStatusCode.InternalServerError, e)
    null
  }

private suspend fun ApplicationCall.decide(deps: VhlDeps, request: ReleaseRequest) {
  when (deps.release.decide(request)) {
    ReleaseOutcome.Denied ->
      respondOutcome(HttpStatusCode.Forbidden, "forbidden", Exception("release denied"))
  }
}
