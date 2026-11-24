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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.cio.CIO as ServerCio
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.net.URI
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

private const val LINK_ISSUER_COUNTRY = "XU"
private const val PORT = 8080
private const val TRUST_DOMAINS_RESOURCE = "/CodeSystem-TrustDemoTrustDomain.json"

fun main() {
  val env = System.getenv()

  fun required(name: String) = checkNotNull(env[name]) { "$name is not set" }

  val trustList =
    ClinicTrustList(
      source =
        HttpTrustListSource(
          HttpClient(CIO),
          env["TRUST_LIST_URL"] ?: "http://gdhcn/local-test-list.json",
        ),
      signer = File(required("TRUST_LIST_SIGNER_PATH")).readText(),
      trustDomains = checkNotNull(TRUST_DOMAINS_RESOURCE.readResource()),
    )
  val database = "jdbc:h2:file:${required("VHL_DB_PATH")};DB_CLOSE_DELAY=-1"
  val hapi = FhirBackend(required("HAPI_URL"), CIO.create())
  val passcodes = H2PasscodeStore(database)
  val deps =
    VhlDeps(
      publicUrl =
        env["VHL_PUBLIC_URL"]
          ?: "https://vhl.onesa.${required("STACK_HOST")}:${required("VHL_TLS_PORT")}",
      issuer = required("TOKEN_ISSUER"),
      allowedClients = required("VHL_ALLOWED_CLIENTS").split(",").map { it.trim() }.toSet(),
      jwks =
        JwkProviderBuilder(URI(required("JWKS_URL")).toURL())
          .cached(10, 1, TimeUnit.HOURS)
          .rateLimited(10, 1, TimeUnit.MINUTES)
          .build(),
      hapi = hapi,
      gateway = FhirBackend(required("GATEWAY_URL"), CIO.create(), relaysCaller = true),
      signer =
        LinkSigner.fromPem(
          File(required("LINK_KEY_FILE")).readText(),
          File(required("LINK_CERT_FILE")).readText(),
          LINK_ISSUER_COUNTRY,
        ),
      passcodes = passcodes,
      clinicAnchor = trustList,
      release = ReleaseRule(hapi, passcodes, H2ReleaseStore(database), Clock.systemUTC()),
    )
  embeddedServer(ServerCio, port = PORT) {
      vhlModule(deps)
      launch { trustList.keepFresh() }
    }
    .start(wait = true)
}

private fun String.readResource(): String? = object {}.javaClass.getResource(this)?.readText()
