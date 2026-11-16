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
import java.security.SecureRandom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val FAMILY_GROUP = "/Trust demo family"
private const val EMAIL_DOMAIN = "trust-demo.example"
private const val PASSWORD_LENGTH = 16
private const val PASSWORD_CHARACTERS = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** A family login just created: the one-time [password] is shown once and is not kept. */
data class FamilyLogin(
  val username: String,
  val password: String,
  val patient: String,
  val household: String,
)

/**
 * Gives a parent registered on Onesa a family-wallet login. The wallet finds the household through
 * the token's `fhirUser`: the parent's Patient links to a RelatedPerson, and the household is the
 * Group that RelatedPerson manages, which is the informant of the newborn's registration. A parent
 * the household's manager is not cannot see it, so that is checked before anything is written.
 */
class Families(
  private val onesa: FhirServer,
  private val keycloak: KeycloakAdmin,
  private val newPassword: () -> String = ::generatedPassword,
) {
  suspend fun add(patientId: String, username: String): FamilyLogin {
    val patient =
      onesa.search("Patient", mapOf("_id" to patientId)).firstOrNull()
        ?: throw CliktError("Patient/$patientId is not on Onesa — register the household first")
    val household = managedHousehold(patientId, patient.fields())
    if (keycloak.usernameTaken(username)) {
      throw CliktError("Keycloak already has a user named $username")
    }
    keycloak.loginsFor("Patient/$patientId").firstOrNull()?.let {
      throw CliktError("Patient/$patientId already signs in as $it")
    }
    val password = newPassword()
    keycloak.create(user(username, password, patientId, patient.fields()))
    return FamilyLogin(username, password, "Patient/$patientId", household)
  }

  private suspend fun managedHousehold(patientId: String, patient: JsonObject): String {
    val related =
      patient["link"]?.jsonArray.orEmpty().mapNotNull {
        it.jsonObject["other"]?.jsonObject?.text("reference")
      }
    if (related.none { it.startsWith("RelatedPerson/") }) {
      throw CliktError(
        "Patient/$patientId has no link to a RelatedPerson, so the wallet cannot find a household: " +
          "register a newborn in J2 (Register Household) with this parent first"
      )
    }
    onesa
      .search("Group", mapOf("managing-entity" to related.joinToString(",")))
      .firstOrNull()
      ?.let {
        return "Group/${it.id}"
      }
    val manager =
      onesa.search("Group", mapOf("member" to "Patient/$patientId")).firstNotNullOfOrNull {
        it.fields()["managingEntity"]?.jsonObject?.text("reference")
      }
    throw CliktError(
      "No household is managed by Patient/$patientId" +
        (manager?.let { " — its manager is $it, another parent" } ?: "") +
        ": the wallet finds a household only through its manager, the parent the J2 form " +
        "names to hold the family's health wallet. Register the newborn in J2 with this parent " +
        "holding the wallet"
    )
  }

  private fun user(username: String, password: String, patientId: String, patient: JsonObject) =
    buildJsonObject {
      val name = patient["name"]?.jsonArray?.firstOrNull()?.jsonObject
      put("username", username)
      put("enabled", true)
      put("emailVerified", true)
      put(
        "firstName",
        name?.get("given")?.jsonArray?.firstOrNull()?.let { (it as JsonPrimitive).content }
          ?: username,
      )
      put("lastName", name?.text("family") ?: "(TEST ONLY)")
      put("email", "$username@$EMAIL_DOMAIN")
      put(
        "attributes",
        buildJsonObject { put("fhirUser", JsonArray(listOf(JsonPrimitive("Patient/$patientId")))) },
      )
      put("groups", buildJsonArray { add(JsonPrimitive(FAMILY_GROUP)) })
      put("requiredActions", buildJsonArray { add(JsonPrimitive("UPDATE_PASSWORD")) })
      put(
        "credentials",
        buildJsonArray {
          add(
            buildJsonObject {
              put("type", "password")
              put("value", password)
              put("temporary", true)
            }
          )
        },
      )
    }
}

/** A random one-time password of letters and digits, without the look-alikes. */
fun generatedPassword(): String {
  val random = SecureRandom()
  return (1..PASSWORD_LENGTH)
    .map { PASSWORD_CHARACTERS[random.nextInt(PASSWORD_CHARACTERS.length)] }
    .joinToString("")
}
