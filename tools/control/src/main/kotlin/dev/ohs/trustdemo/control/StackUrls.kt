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
import java.nio.file.Path

private const val DEFAULT_WATCH_PORT = 18090
private const val DEFAULT_KEYCLOAK_PORT = 18083

/**
 * Where the stack's services are, worked out from either env file: `stack/.env` (hostnames under
 * sslip.io, HTTP on the proxy port, HTTPS only for VHL and the facility client) or `infra/.env`
 * (HTTPS on 443 under the base domain, HAPI and Keycloak on loopback).
 */
class StackUrls
private constructor(
  val hapi: String,
  val keycloak: String,
  val keycloakAdmin: String,
  val gateway: String,
  val medplum: String,
  val clientOrigin: String,
  val vhl: String,
  val trust: String,
  val watchPort: Int,
  val secretsDir: Path,
) {
  companion object {
    fun from(env: StackEnv, envFile: Path): StackUrls {
      val envDir = envFile.parent ?: Path.of(".")
      val watchPort = env.optional("WATCH_PORT")?.toInt() ?: DEFAULT_WATCH_PORT
      val stackHost = env.optional("STACK_HOST")
      if (stackHost != null) {
        val http = "$stackHost:${env.require("PROXY_PORT")}"
        val tls = "$stackHost:${env.require("VHL_TLS_PORT")}"
        return StackUrls(
          hapi = "http://127.0.0.1:${env.require("HAPI_HOST_PORT")}/fhir",
          keycloak = "http://keycloak.onesa.$http",
          keycloakAdmin =
            "http://127.0.0.1:${env.optional("KEYCLOAK_HOST_PORT") ?: DEFAULT_KEYCLOAK_PORT}",
          gateway = "http://gateway.onesa.$http",
          medplum = "http://medplum.kesaro.$http",
          clientOrigin = "https://client.kesaro.$tls",
          vhl = "https://vhl.onesa.$tls",
          trust = "http://trust.gdhcn.$http",
          watchPort = watchPort,
          secretsDir = envDir.resolve("taifafeki/secrets"),
        )
      }
      val base =
        env.optional("BASE_DOMAIN")
          ?: throw CliktError(
            "$envFile has neither STACK_HOST nor BASE_DOMAIN: not a stack env file"
          )
      fun host(variable: String, name: String) =
        "https://${env.optional(variable) ?: "$name.$base"}"
      return StackUrls(
        hapi = "http://127.0.0.1:${env.require("HAPI_FHIR_PORT")}/fhir",
        keycloak = host("KEYCLOAK_HOST", "keycloak.onesa"),
        keycloakAdmin = "http://127.0.0.1:${env.optional("KEYCLOAK_PORT") ?: "8081"}",
        gateway = host("GATEWAY_HOST", "gateway.onesa"),
        medplum = host("MEDPLUM_HOST", "medplum.kesaro"),
        clientOrigin = host("CLIENT_HOST", "client.kesaro"),
        vhl = host("VHL_HOST", "vhl.onesa"),
        trust = host("TRUST_HOST", "trust.gdhcn"),
        watchPort = watchPort,
        secretsDir = envDir.resolve("../stack/taifafeki/secrets").normalize(),
      )
    }
  }
}
