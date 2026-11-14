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

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.server.cio.CIO as ServerCio
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

private const val LINK_ISSUER_COUNTRY = "XK"
private const val PORT = 8080

fun main() {
  val env = System.getenv()

  fun required(name: String) = checkNotNull(env[name]) { "$name is not set" }

  val deps =
    VhlDeps(
      publicUrl = "https://vhl.onesa.${required("STACK_HOST")}:${required("VHL_TLS_PORT")}",
      issuer = required("TOKEN_ISSUER"),
      allowedClients = required("VHL_ALLOWED_CLIENTS").split(",").map { it.trim() }.toSet(),
      jwks =
        JwkProviderBuilder(URI(required("JWKS_URL")).toURL())
          .cached(10, 1, TimeUnit.HOURS)
          .rateLimited(10, 1, TimeUnit.MINUTES)
          .build(),
      hapi = FhirBackend(required("HAPI_URL"), CIO.create()),
      gateway = FhirBackend(required("GATEWAY_URL"), CIO.create(), relaysCaller = true),
      signer =
        LinkSigner.fromPem(
          File(required("LINK_KEY_FILE")).readText(),
          File(required("LINK_CERT_FILE")).readText(),
          LINK_ISSUER_COUNTRY,
        ),
      passcodes = DiscardPasscodes,
    )
  embeddedServer(ServerCio, port = PORT) { vhlModule(deps) }.start(wait = true)
}
