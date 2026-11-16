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

import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.trustdemo.trustkit.CardKey
import dev.ohs.trustdemo.trustkit.CardSigner
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val RECORD_ID = Regex("[A-Za-z0-9][A-Za-z0-9\\-.]{0,63}")
private val PATIENT_REFERENCE = Regex("Patient/($RECORD_ID)")
private val fhirJson = Json { ignoreUnknownKeys = true }

class IssuerDeps(val medplum: MedplumReader, val key: CardKey)

fun Application.issuerModule(deps: IssuerDeps) {
  routing {
    get("/issuer/health") { call.respondText("ok") }
    post("/issuer/\$issue") {
      val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
      val id =
        runCatching { fhirJson.parseToJsonElement(call.receiveText()).jsonObject }
          .getOrNull()
          ?.get("immunization")
          ?.jsonPrimitive
          ?.content
          ?.takeIf(RECORD_ID::matches)
      when {
        token.isNullOrBlank() ->
          call.refuse(HttpStatusCode.Unauthorized, "Send the caller's Medplum token")
        id == null ->
          call.refuse(HttpStatusCode.BadRequest, "Name the dose as {\"immunization\": id}")
        else -> call.issue(deps, token, id)
      }
    }
  }
}

private suspend fun ApplicationCall.issue(deps: IssuerDeps, token: String, id: String) {
  try {
    val dose =
      fhirJson.decodeFromString(
        Immunization.serializer(),
        deps.medplum.read("Immunization/$id", token),
      )
    val reference = dose.patient.reference?.value.orEmpty()
    if (
      dose.status.value != Immunization.ImmunizationStatusCodes.Completed ||
        !PATIENT_REFERENCE.matches(reference)
    ) {
      return refuse(
        HttpStatusCode.UnprocessableEntity,
        "Only a completed dose of a patient gets a card",
      )
    }
    val patient =
      fhirJson.decodeFromString(Patient.serializer(), deps.medplum.read(reference, token))
    respondText(CardSigner.sign(dose, patient, key = deps.key))
  } catch (refused: MedplumRefused) {
    val passedOn = refused.status in setOf(401, 403, 404)
    refuse(
      if (passedOn) HttpStatusCode.fromValue(refused.status) else HttpStatusCode.BadGateway,
      refused.message.orEmpty(),
    )
  } catch (_: NullPointerException) {
    refuse(HttpStatusCode.UnprocessableEntity, "The records lack what a card carries")
  } catch (_: NoSuchElementException) {
    refuse(HttpStatusCode.UnprocessableEntity, "The records lack what a card carries")
  }
}

private suspend fun ApplicationCall.refuse(status: HttpStatusCode, reason: String) =
  respondText(reason, status = status)
