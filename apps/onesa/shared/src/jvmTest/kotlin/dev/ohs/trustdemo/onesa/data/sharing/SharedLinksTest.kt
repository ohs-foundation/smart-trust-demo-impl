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
package dev.ohs.trustdemo.onesa.data.sharing

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class SharedLinksTest {
  private val today = LocalDate.parse("2026-11-14")
  private val repository = InMemorySampleFhirRepository()
  private val links = InMemoryLinks()
  private val requests = mutableListOf<Triple<HttpMethod, String, String>>()
  private var refreshes = 0

  private var family: String? = "bounmy"

  private fun linksOver(
    status: HttpStatusCode,
    refresh: suspend () -> Boolean = {
      refreshes++
      true
    },
  ): SharedLinks =
    SharedLinks(
      repository = repository,
      accessToken = { "token" },
      httpClient =
        HttpClient(
          MockEngine { request ->
            requests +=
              Triple(
                request.method,
                request.url.encodedPath,
                request.body.toByteArray().decodeToString(),
              )
            respond("{}", status, headersOf(HttpHeaders.ContentType, "application/fhir+json"))
          }
        ),
      baseUrl = "https://vhl.test:8443",
      links = links,
      names = { if (it == "somsak") "Somsak" else null },
      family = { family },
      refresh = refresh,
      today = { today },
    )

  private suspend fun twoSharesForSomsak() {
    listOf(consent("c1"), consent("c2")).forEach { repository.upsert(decode(it)) }
    links.keep(
      listOf(
        IssuedLink("c1", "somsak", "Somsak", "HC1:one", exp = 9),
        IssuedLink("c2", "somsak", "Somsak", "HC1:two", exp = 9),
      )
    )
  }

  @Test
  fun whoLookedShowsOnlyThisSharesAccesses() = runTest {
    twoSharesForSomsak()
    listOf(
        audit("a1", "c1", "2026-11-14T09:00:00Z", "0"),
        audit("a2", "c1", "2026-11-14T09:05:00Z", "4", reason = "wrong-passcode"),
        audit("a3", "c2", "2026-11-14T09:10:00Z", "0"),
        audit("a4", null, "2026-11-14T09:15:00Z", "4", reason = "wrong-passcode"),
      )
      .forEach { repository.upsert(decode(it)) }

    val shares = linksOver(HttpStatusCode.OK).list().associateBy { it.consentId }

    val first = shares.getValue("c1")
    assertEquals(listOf("a2", "a1"), first.looks.map { it.id })
    assertEquals(listOf("Kesaro clinic", "Kesaro clinic"), first.looks.map { it.clinic })
    assertEquals(setOf("clinic-viewer.kesaro.example"), first.looks.map { it.host }.toSet())
    assertEquals(listOf(false, true), first.looks.map { it.released })
    assertEquals(listOf(Refusal.WrongPasscode, null), first.looks.map { it.refusal })
    assertEquals(listOf("a3"), shares.getValue("c2").looks.map { it.id })
    assertEquals("Somsak", first.name)
    assertEquals(LocalDate.parse("2026-11-28"), first.end)
  }

  @Test
  fun aGatewayReadOfTheConsentIsNotALook() = runTest {
    twoSharesForSomsak()
    repository.upsert(decode(gatewayRead("r1", "Consent/c1")))

    assertEquals(emptyList(), linksOver(HttpStatusCode.OK).list().flatMap { it.looks })
  }

  @Test
  fun withdrawingOneShareLeavesTheOtherWorking() = runTest {
    twoSharesForSomsak()
    val shared = linksOver(HttpStatusCode.OK)

    val outcome = shared.withdraw("c1")

    assertEquals(WithdrawOutcome.Withdrawn(), outcome)
    assertEquals(listOf(Triple(HttpMethod.Post, "/vhl/\$withdraw", withdrawBody("c1"))), requests)
    assertEquals(setOf("c2"), links.kept.keys)
    assertEquals("HC1:two", links.kept.getValue("c2").link)
    val shares = shared.list().associateBy { it.consentId }
    assertEquals(ShareStatus.Withdrawn, shares.getValue("c1").status)
    assertEquals(false, shares.getValue("c1").kept)
    assertEquals(ShareStatus.Active, shares.getValue("c2").status)
    assertEquals(true, shares.getValue("c2").kept)
    assertEquals(1, refreshes)
  }

  @Test
  fun aFailedRefreshStillReportsTheWithdrawal() = runTest {
    twoSharesForSomsak()
    val shared = linksOver(HttpStatusCode.OK, refresh = { error("offline") })

    assertEquals(WithdrawOutcome.Withdrawn(listStale = true), shared.withdraw("c1"))
    assertEquals(setOf("c2"), links.kept.keys)
  }

  @Test
  fun aConflictAsksTheUserToRetry() = runTest {
    twoSharesForSomsak()
    val shared = linksOver(HttpStatusCode.Conflict)

    val outcome = shared.withdraw("c1")

    assertIs<WithdrawOutcome.Changed>(outcome)
    assertEquals(setOf("c1", "c2"), links.kept.keys)
    assertEquals(ShareStatus.Active, shared.list().first { it.consentId == "c1" }.status)
    assertEquals(1, refreshes)
  }

  @Test
  fun sharedLinksShowOnlyTheSignedInFamilysShares() = runTest {
    twoSharesForSomsak()
    repository.upsert(decode(consent("c3", performer = "Patient/khamla")))
    val shared = linksOver(HttpStatusCode.OK)

    assertEquals(listOf("c1", "c2"), shared.list().map { it.consentId }.sorted())

    family = "khamla"
    assertEquals(listOf("c3"), shared.list().map { it.consentId })

    family = null
    assertEquals(emptyList(), shared.list().map { it.consentId })
  }

  private fun withdrawBody(consentId: String) =
    """{"resourceType":"Parameters","parameter":[{"name":"consent","valueReference":{"reference":"Consent/$consentId"}}]}"""

  private fun decode(json: String): Resource =
    FhirJson.instance.decodeFromString(Resource.serializer(), json)

  private fun consent(id: String, performer: String = "Patient/bounmy") =
    """{"resourceType":"Consent","id":"$id","status":"active",
      "scope":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/consentscope","code":"patient-privacy"}]},
      "category":[{"coding":[{"system":"http://loinc.org","code":"59284-0"}]}],
      "patient":{"reference":"Patient/somsak"},
      "performer":[{"reference":"$performer"}],
      "policyRule":{"coding":[{"system":"http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlConsent","code":"CONSENT-VHL-TREATMENT"}]},
      "provision":{"type":"permit","period":{"start":"2026-11-14","end":"2026-11-28"}}}"""

  private fun audit(
    id: String,
    consentId: String?,
    recorded: String,
    outcome: String,
    reason: String? = null,
  ): String {
    val subtype =
      listOfNotNull(
          """{"display":"IHE ITI VHL manifest retrieval (ITI-YY5)"}""",
          reason?.let {
            """{"system":"http://smart.who.int/trust-demo/CodeSystem/TrustDemoVhlRefusalReason","code":"$it"}"""
          },
        )
        .joinToString(",")
    val entities =
      listOfNotNull(
          """{"what":{"reference":"Patient/somsak"}}""",
          consentId?.let { """{"what":{"reference":"Consent/$it"}}""" },
        )
        .joinToString(",")
    return """{"resourceType":"AuditEvent","id":"$id",
      "meta":{"profile":["http://smart.who.int/trust-demo/StructureDefinition/TrustDemoOnesaVhlAccess"]},
      "type":{"system":"http://dicom.nema.org/resources/ontology/DCM","code":"110112"},
      "subtype":[$subtype],"action":"E","recorded":"$recorded","outcome":"$outcome",
      "agent":[{"who":{"display":"Kesaro clinic"},"name":"Kesaro clinic","requestor":true,
                "network":{"address":"clinic-viewer.kesaro.example","type":"1"}},
               {"who":{"display":"Onesa VHL service"},"requestor":false}],
      "source":{"observer":{"display":"Onesa VHL service"}},
      "entity":[$entities]}"""
  }

  private fun gatewayRead(id: String, what: String) =
    """{"resourceType":"AuditEvent","id":"$id",
      "meta":{"profile":["https://profiles.ihe.net/ITI/BALP/StructureDefinition/IHE.BasicAudit.PatientRead"]},
      "type":{"system":"http://terminology.hl7.org/CodeSystem/audit-event-type","code":"rest"},
      "subtype":[{"system":"http://hl7.org/fhir/restful-interaction","code":"read"}],
      "action":"R","recorded":"2026-11-14T09:20:00Z","outcome":"0",
      "agent":[{"who":{"identifier":{"system":"http://keycloak.test/realms/onesa","value":"bounmy-sub"},
                "display":"bounmy"},"requestor":true}],
      "source":{"observer":{"display":"http://gateway.test/fhir"}},
      "entity":[{"what":{"reference":"Patient/somsak"},
                 "role":{"system":"http://terminology.hl7.org/CodeSystem/object-role","code":"1"}},
                {"what":{"reference":"$what"},
                 "role":{"system":"http://terminology.hl7.org/CodeSystem/object-role","code":"4"}}]}"""
}
