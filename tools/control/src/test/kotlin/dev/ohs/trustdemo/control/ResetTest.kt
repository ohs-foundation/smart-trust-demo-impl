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

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.Subscription
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FakeHapi {
  val stored = mutableMapOf<String, Resource>()
  var expunges = 0

  private val engine = MockEngine { request ->
    val path = request.url.encodedPath.removePrefix("/fhir")
    when {
      request.method == HttpMethod.Get && path == "/metadata" -> respond("{}", headers = fhirJson)
      request.method == HttpMethod.Post && path == "/\$expunge" -> {
        expunges++
        stored.clear()
        respond("{}", headers = fhirJson)
      }
      request.method == HttpMethod.Post && path == "" -> {
        val body = (request.body as TextContent).text
        val bundle = Json.decodeFromString(Resource.serializer(), body) as Bundle
        bundle.entry.forEach { entry ->
          val resource = checkNotNull(entry.resource)
          stored["${resource.resourceType()}/${resource.id}"] = resource
        }
        respond(body, headers = fhirJson)
      }
      else -> error("unexpected ${request.method} ${request.url}")
    }
  }

  val server = FhirServer("http://hapi.test/fhir", { null }, engine)

  private companion object {
    val fhirJson = headersOf(HttpHeaders.ContentType, "application/fhir+json")
  }
}

private const val WATCH_PORT = 18090
private const val CLIENT_ORIGIN = "http://client.test:8480"

class ResetTest {
  private val today = LocalDate.of(2026, 11, 14)

  @Test
  fun resetTwiceLeavesOnlyBaseData() = runTest {
    val hapi = FakeHapi()
    val medplum = FakeMedplum()
    val secrets = createTempDirectory()
    repeat(2) {
      Reset(hapi.server, medplum.admin, "password", secrets, WATCH_PORT, CLIENT_ORIGIN).run(today)
    }
    assertEquals(2, hapi.expunges)
    assertEquals(Seed.onesaBase(ig).entry.size + 1, hapi.stored.size)
    assertTrue(medplum.stored.keys.none { it.startsWith("DocumentReference/") })
    assertEquals(1, medplum.invites)
    assertEquals(
      medplum.stored.keys.single { it.startsWith("ClientApplication/") }.substringAfter('/'),
      secrets.resolve("medplum-client-id").readText(),
    )
    assertEquals(
      setOf("clinic-viewer.crt", "clinic-viewer.key", "medplum-client-id"),
      secrets.listDirectoryEntries().map { it.name }.toSet(),
    )
  }

  @Test
  fun resetRegistersOneSubscriptionWithNoPayload() = runTest {
    val hapi = FakeHapi()
    repeat(2) {
      Reset(
          hapi.server,
          FakeMedplum().admin,
          "password",
          createTempDirectory(),
          WATCH_PORT,
          CLIENT_ORIGIN,
        )
        .run(today)
    }
    val subscription = hapi.stored.values.filterIsInstance<Subscription>().single()
    assertEquals(WATCH_CRITERIA, subscription.criteria.value)
    assertEquals(
      "http://host.docker.internal:$WATCH_PORT/hook",
      subscription.channel.endpoint?.value,
    )
    assertNull(subscription.channel.payload)
  }

  @Test
  fun resetWritesSomsaksJ1RecordsAsTheClientDoes() = runTest {
    val medplum = resetInto(FakeMedplum())
    val patient = medplum.stored.values.single { it.type() == "Patient" }
    val doses = medplum.stored.values.filter { it.type() == "Immunization" }
    val shift = ChronoUnit.DAYS.between(IG_J3, today)
    val hospital = medplum.stored.entries.single { it.value.igId() == "TrustDemoKesaroHospital" }

    assertEquals(
      "http://smart.who.int/trust-demo/StructureDefinition/TrustDemoTaifafekiPatient",
      patient.profile(),
    )
    assertTrue(patient.hasIdentifier("CRH-HN-68-104377"))
    assertEquals(IG_BIRTH.plusDays(shift).toString(), patient.text("birthDate"))
    assertEquals(
      hospital.key,
      patient.getValue("managingOrganization").jsonObject.text("reference"),
    )
    assertEquals(2, doses.size)
    doses.forEach { dose ->
      assertEquals(
        "http://smart.who.int/trust-demo/StructureDefinition/TrustDemoTaifafekiImmunization",
        dose.profile(),
      )
      assertEquals("completed", dose.text("status"))
      assertTrue(dose.text("occurrenceDateTime")!!.startsWith(IG_BIRTH.plusDays(shift).toString()))
      assertEquals(
        "Patient/${patient.text("id")}",
        dose.getValue("patient").jsonObject.text("reference"),
      )
      assertEquals(
        medplum.stored.entries.single { it.value.igId() == "TrustDemoAnan" }.key,
        dose
          .getValue("performer")
          .jsonArray
          .single()
          .jsonObject
          .getValue("actor")
          .jsonObject
          .text("reference"),
      )
    }
    assertTrue(medplum.stored.keys.none { it.startsWith("DocumentReference/") })
  }

  @Test
  fun resetMeetsJ1AndLeavesJ2Next() = runTest {
    val medplum = resetInto(FakeMedplum())
    val state = stateOf(medplum, today)
    assertTrue(state.isDone(Step.J1))
    assertEquals(2, state.metCount(Step.J1))
    assertEquals(Step.J2, StepEngine.currentStep(state))
  }

  @Test
  fun resetTwiceLeavesOneSomsakAndTwoDoses() = runTest {
    val medplum = FakeMedplum()
    repeat(2) { resetInto(medplum) }
    assertEquals(1, medplum.stored.values.count { it.type() == "Patient" })
    assertEquals(2, medplum.stored.values.count { it.type() == "Immunization" })
  }

  @Test
  fun beforeJ1WritesNoSomsak() = runTest {
    val medplum = resetInto(FakeMedplum(), beforeJ1 = true)
    assertTrue(medplum.stored.values.none { it.type() in listOf("Patient", "Immunization") })
    assertEquals(Step.J1, StepEngine.currentStep(stateOf(medplum, today)))
  }

  private suspend fun resetInto(medplum: FakeMedplum, beforeJ1: Boolean = false): FakeMedplum {
    Reset(
        FakeHapi().server,
        medplum.admin,
        "password",
        createTempDirectory(),
        WATCH_PORT,
        CLIENT_ORIGIN,
      )
      .run(today, beforeJ1)
    return medplum
  }

  private suspend fun stateOf(medplum: FakeMedplum, resetDay: LocalDate): StoryState {
    val patients = medplum.admin.searchResources("Patient", mapOf("identifier" to HOSPITAL_NUMBER))
    val doses = medplum.admin.searchResources("Immunization", emptyMap())
    val labels = expectations.getValue(Step.J1).map { it.label }
    return StoryState(resetDay, resetDay, mapOf(labels[0] to patients, labels[1] to doses))
  }

  private fun JsonObject.type() = text("resourceType")

  private fun JsonObject.profile() =
    getValue("meta").jsonObject.getValue("profile").jsonArray.single().jsonPrimitive.content
}
