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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val LOCAL =
  mapOf(
    "STACK_HOST" to "192.0.2.10.sslip.io",
    "PROXY_PORT" to "8480",
    "VHL_TLS_PORT" to "8443",
    "HAPI_HOST_PORT" to "18082",
    "WATCH_PORT" to "18090",
  )

private val LIVE =
  mapOf(
    "BASE_DOMAIN" to "demo.example.org",
    "KEYCLOAK_HOST" to "keycloak.onesa.\${BASE_DOMAIN}",
    "HAPI_FHIR_PORT" to "8082",
  )

class StackUrlsTest {
  @Test
  fun `local urls are http on the proxy port, with TLS only for VHL and the client`() {
    val urls = StackUrls.from(StackEnv(LOCAL), Path.of("stack/.env"))
    assertEquals("http://127.0.0.1:18082/fhir", urls.hapi)
    assertEquals("http://medplum.kesaro.192.0.2.10.sslip.io:8480", urls.medplum)
    assertEquals("https://client.kesaro.192.0.2.10.sslip.io:8443", urls.clientOrigin)
    assertEquals("http://keycloak.onesa.192.0.2.10.sslip.io:8480", urls.keycloak)
    assertEquals("http://gateway.onesa.192.0.2.10.sslip.io:8480", urls.gateway)
    assertEquals("http://127.0.0.1:18083", urls.keycloakAdmin)
    assertEquals("https://vhl.onesa.192.0.2.10.sslip.io:8443", urls.vhl)
    assertEquals("http://trust.gdhcn.192.0.2.10.sslip.io:8480", urls.trust)
    assertEquals(18090, urls.watchPort)
    assertEquals(Path.of("stack/taifafeki/secrets"), urls.secretsDir)
  }

  @Test
  fun `live urls are https on 443, from the host variables or the base domain`() {
    val urls = StackUrls.from(StackEnv(LIVE), Path.of("infra/.env"))
    assertEquals("http://127.0.0.1:8082/fhir", urls.hapi)
    assertEquals("https://keycloak.onesa.demo.example.org", urls.keycloak)
    assertEquals("https://gateway.onesa.demo.example.org", urls.gateway)
    assertEquals("http://127.0.0.1:8081", urls.keycloakAdmin)
    assertEquals("https://medplum.kesaro.demo.example.org", urls.medplum)
    assertEquals("https://client.kesaro.demo.example.org", urls.clientOrigin)
    assertEquals("https://vhl.onesa.demo.example.org", urls.vhl)
    assertEquals("https://trust.gdhcn.demo.example.org", urls.trust)
    assertEquals(18090, urls.watchPort)
    assertEquals(Path.of("stack/taifafeki/secrets"), urls.secretsDir)
  }

  @Test
  fun `a named host wins over the base domain`() {
    val env = StackEnv(LIVE + ("MEDPLUM_HOST" to "med.other.example"))
    assertEquals("https://med.other.example", StackUrls.from(env, Path.of("infra/.env")).medplum)
  }

  @Test
  fun `the secrets follow the env file`() {
    assertEquals(
      Path.of("/srv/demo/stack/taifafeki/secrets"),
      StackUrls.from(StackEnv(LIVE), Path.of("/srv/demo/infra/.env")).secretsDir,
    )
    assertEquals(
      Path.of("/srv/demo/stack/taifafeki/secrets"),
      StackUrls.from(StackEnv(LOCAL), Path.of("/srv/demo/stack/.env")).secretsDir,
    )
  }

  @Test
  fun `a file with neither shape is refused`() {
    assertFailsWith<CliktError> { StackUrls.from(StackEnv(emptyMap()), Path.of("x/.env")) }
  }
}
