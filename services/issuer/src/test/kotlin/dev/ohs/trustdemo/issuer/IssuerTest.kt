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
package dev.ohs.trustdemo.issuer

import dev.ohs.trustdemo.trustkit.CardKey
import dev.ohs.trustdemo.trustkit.TestKeys
import dev.ohs.trustdemo.trustkit.TrustListWriter
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import who.smart.trust.core.DEFAULT_LIMITS
import who.smart.trust.core.Hcert
import who.smart.trust.payloads.Payload
import who.smart.trust.verifier.VerificationResult
import who.smart.trust.verifier.VerifierOptions

private const val BABY = "Patient/baby-9"
private const val DOSE = "Immunization/dose-9"

private val patient =
  """{"resourceType":"Patient","id":"baby-9","name":[{"given":["Amara"],"family":"OKELLO"}],
    "gender":"female","birthDate":"2026-11-10"}"""
private val immunization =
  """{"resourceType":"Immunization","id":"dose-9","status":"completed",
    "patient":{"reference":"$BABY"},"occurrenceDateTime":"2026-11-10T08:30:00+03:00",
    "lotNumber":"LOT-77",
    "vaccineCode":{"coding":[{"system":"http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualProductIds","code":"a3K3X000006MiisUAC"}]}}"""

/** A Medplum that serves the two records to the token [accepts] and refuses every other. */
private class FakeMedplum(private val accepts: String = "good") {
  val seen = mutableListOf<Pair<String, String>>()

  val engine = MockEngine { request ->
    val path = request.url.encodedPath.removePrefix("/fhir/R4/")
    val token = request.headers[HttpHeaders.Authorization]
    seen += path to token.orEmpty()
    val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    when {
      token == null -> respond("{}", HttpStatusCode.Unauthorized, json)
      token != "Bearer $accepts" -> respond("{}", HttpStatusCode.Forbidden, json)
      path == DOSE -> respond(immunization, HttpStatusCode.OK, json)
      path == BABY -> respond(patient, HttpStatusCode.OK, json)
      else -> respond("{}", HttpStatusCode.NotFound, json)
    }
  }
}

class IssuerTest {
  private val now = Instant.parse("2026-11-16T00:00:00Z").epochSecond

  private fun withService(medplum: FakeMedplum, block: suspend ApplicationTestBuilder.() -> Unit) =
    testApplication {
      application {
        issuerModule(
          IssuerDeps(MedplumReader("http://medplum:8103/fhir/R4", medplum.engine), CardKey.testkit)
        )
      }
      block()
    }

  private suspend fun ApplicationTestBuilder.issue(id: String, token: String? = "good") =
    client.post("/issuer/\$issue") {
      token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"immunization":"$id"}""")
    }

  private fun verified(hc1: String): VerificationResult.Verified {
    val store =
      Hcert.trust.newTrustStore().apply {
        loadTrustDomains(
          checkNotNull(javaClass.getResource("/CodeSystem-TrustDemoTrustDomain.json")).readText()
        )
        addVerifiedDidDocument(TrustListWriter.localTestList(), TestKeys.gdhcnSignerDidDocument())
      }
    val result =
      Hcert.verifier.verify(
        hc1,
        store,
        Hcert.subclaims.newRegistry(),
        now,
        VerifierOptions(DEFAULT_LIMITS, clockSkewSeconds = 300),
      )
    return assertIs<VerificationResult.Verified>(result)
  }

  @Test
  fun signsACardForAnyNewbornThatVerifiesAgainstTheCardKey() {
    val medplum = FakeMedplum()
    withService(medplum) {
      val response = issue("dose-9")
      assertEquals(HttpStatusCode.OK, response.status)
      val hc1 = response.bodyAsText()
      assertTrue(hc1.startsWith("HC1:"))
      val dvc = assertIs<Payload.DvcMin>(verified(hc1).value.entries.single().payload).value
      assertEquals(
        listOf("Amara OKELLO", "2026-11-10", "female", "XXR"),
        listOf(dvc.n, dvc.dob, dvc.s, dvc.nt),
      )
      assertEquals(
        listOf("a3K3X000006MiisUAC", "2026-11-10", "XXR", "LOT-77"),
        listOf(dvc.vx.vp, dvc.vx.dv, dvc.vx.`is`, dvc.vx.bo),
      )
    }
  }

  @Test
  fun readsBothRecordsWithTheCallersToken() {
    val medplum = FakeMedplum()
    withService(medplum) {
      issue("dose-9")
      assertEquals(listOf(DOSE to "Bearer good", BABY to "Bearer good"), medplum.seen)
    }
  }

  @Test
  fun refusesACallerMedplumRefuses() {
    val medplum = FakeMedplum()
    withService(medplum) {
      assertEquals(HttpStatusCode.Forbidden, issue("dose-9", token = "stranger").status)
      assertEquals(HttpStatusCode.NotFound, issue("missing").status)
    }
  }

  @Test
  fun refusesACallerWithoutAToken() {
    val medplum = FakeMedplum()
    withService(medplum) {
      assertEquals(HttpStatusCode.Unauthorized, issue("dose-9", token = null).status)
      assertTrue(medplum.seen.isEmpty())
    }
  }
}
