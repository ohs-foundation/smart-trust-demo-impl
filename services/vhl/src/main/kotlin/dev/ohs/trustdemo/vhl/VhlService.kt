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

import com.auth0.jwk.JwkProvider
import java.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import who.smart.trust.HcertResult
import who.smart.trust.core.Hcert
import who.smart.trust.vhl.VhlPayload

private const val PASSCODE_FLAG = "P"
private const val LINK_LABEL = "Immunization summary"

/**
 * What the service needs from outside: the token check, both FHIR servers, the signer, the clock.
 */
class VhlDeps(
  val publicUrl: String,
  val issuer: String,
  val allowedClients: Set<String>,
  val jwks: JwkProvider,
  val hapi: FhirBackend,
  val gateway: FhirBackend,
  val signer: LinkSigner,
  val passcodes: PasscodeStore,
  val clock: Clock = Clock.systemUTC(),
)

/** Records a consent per person and publishes their IPS behind a signed link. */
class VhlService(private val deps: VhlDeps) {
  suspend fun generate(caller: String, bearer: String, request: ShareRequest): JsonObject {
    val now = deps.clock.instant()
    val records = Records(deps.publicUrl, now)
    if (!request.expires.isAfter(now)) throw BadRequest("the period has already ended")

    val household = household(caller, bearer)
    val outside = request.people.filterNot { it in household }
    if (outside.isNotEmpty()) throw NotYourHousehold("not in your household: $outside")

    val shares =
      request.people.map { person ->
        val patient = deps.gateway.read("Patient/$person", bearer = bearer)
        val immunizations = deps.gateway.search("Immunization", mapOf("patient" to person), bearer)
        records.prepare(caller, patient, immunizations, request)
      }
    val issued =
      shares.map { share ->
        val key = Hcert.vhl.newKey()
        val jwe = ok(Hcert.vhl.encrypt(share.ips.toString().toByteArray(), IPS_TYPE, key))
        val link =
          VhlPayload(
            url = records.manifestUrl(share.listId, share.patient),
            key = key,
            exp = request.expires.epochSecond,
            flag = PASSCODE_FLAG,
            label = LINK_LABEL,
            v = 1,
          )
        Triple(share, jwe, deps.signer.sign(link, now, request.expires))
      }

    issued.forEach { (share, _, _) -> deps.passcodes.keep(share.consent.id(), request.passcode) }
    deps.hapi.transaction(records.transaction(issued.map { (share, jwe, _) -> share to jwe }))
    return response(issued.map { (share, _, hc1) -> share to hc1 })
  }

  /**
   * The patients of the households the caller manages, as the gateway's household check reads them.
   */
  private suspend fun household(caller: String, bearer: String): Set<String> {
    val managers =
      deps.gateway
        .read("Patient/$caller", bearer = bearer)["link"]
        ?.jsonArray
        ?.mapNotNull { it.jsonObject["other"]?.jsonObject?.text("reference") }
        ?.filter { it.startsWith("RelatedPerson/") }
        .orEmpty()
    return managers
      .flatMap { deps.gateway.search("Group", mapOf("managing-entity" to it), bearer) }
      .flatMap { it["member"]?.jsonArray.orEmpty() }
      .map { it.jsonObject }
      .filterNot { it["inactive"]?.jsonPrimitive?.boolean == true }
      .mapNotNull { it["entity"]?.jsonObject?.text("reference") }
      .filter { it.startsWith("Patient/") }
      .map { it.removePrefix("Patient/") }
      .toSet()
  }

  private fun response(issued: List<Pair<Share, String>>) = buildJsonObject {
    put("resourceType", "Parameters")
    putJsonArray("parameter") {
      issued.forEach { (share, hc1) ->
        add(
          buildJsonObject {
            put("name", "link")
            putJsonArray("part") {
              add(part("person", "Patient/${share.patient.id()}"))
              add(
                buildJsonObject {
                  put("name", "link")
                  put("valueString", hc1)
                }
              )
              add(part("consent", "Consent/${share.consent.id()}"))
              add(part("bundle", "Bundle/${share.ips.id()}"))
              add(part("binary", "Binary/${share.binaryId}"))
              add(part("documentReference", "DocumentReference/${share.documentReferenceId}"))
              add(part("list", "List/${share.listId}"))
            }
          }
        )
      }
    }
  }

  private fun part(name: String, reference: String) = buildJsonObject {
    put("name", name)
    putJsonObject("valueReference") { put("reference", reference) }
  }

  private fun <T, E> ok(result: HcertResult<T, E>): T =
    when (result) {
      is HcertResult.Ok -> result.value
      is HcertResult.Err -> error("link encryption failed: ${result.error}")
    }

  private companion object {
    const val IPS_TYPE = "application/fhir+json"
  }
}
