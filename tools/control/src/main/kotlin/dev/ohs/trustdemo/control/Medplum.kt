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

import dev.ohs.fhir.model.r4.CodeSystem
import dev.ohs.fhir.model.r4.Device
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.StructureDefinition
import dev.ohs.fhir.model.r4.ValueSet
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val IG_ID_SYSTEM = "http://ohs.dev/fhir/trust-demo/ig-id"
private const val HOSPITAL = "TrustDemoKesaroHospital"
private const val CLINIC = "TrustDemoKesaroClinic"
private const val ANAN = "TrustDemoAnan"
private const val PROFILE_PREFIX = "TrustDemoTaifafeki"
private const val PROJECT_NAME = "Taifafeki Ministry of Public Health"
private const val POLICY_NAME = "Taifafeki clinician"
private const val CLIENT_NAME = "taifafeki-facility-client"
private const val TENANT_PARAMETER = "organization"
private const val ANAN_EMAIL = "anan@kesaro.example"
private const val PAGE = "1000"
private const val MAX_WIPE_ROUNDS = 50
private val STORY_TYPES =
  listOf("Patient", "Immunization", "DocumentReference", "Provenance", "AuditEvent")
private val READ_ONLY =
  listOf(
    "Organization",
    "Practitioner",
    "PractitionerRole",
    "Location",
    "Device",
    "CodeSystem",
    "ValueSet",
  )
private val READ_WRITE = listOf("Patient", "Immunization", "Provenance", "DocumentReference")
private val IN_COMPARTMENT =
  listOf(
    "Patient",
    "Immunization",
    "Provenance",
    "DocumentReference",
    "Location",
    "PractitionerRole",
    "Device",
  )

private val fhirJson = Json { ignoreUnknownKeys = true }
private val fhirContentType = ContentType.parse("application/fhir+json")

/**
 * The Taifafeki project on Medplum, driven as the server's super admin: its records are written
 * with `meta.project` set, which only a super admin may do, so no project-level credential is kept.
 */
class MedplumAdmin(
  private val baseUrl: String,
  private val clientId: String,
  private val clientSecret: String,
  engine: HttpClientEngine,
) {
  private val client = HttpClient(engine)
  private var token: String? = null
  private var projectId: String? = null

  /** Authenticates and resolves the project, so a stack that is not ready fails before a wipe. */
  suspend fun preflight() {
    project()
  }

  /**
   * Expunges the story types in the project, as left by earlier runs. Binary cannot be searched, so
   * its records go with the resources that point at them.
   */
  suspend fun wipeStory() {
    val project = project()
    STORY_TYPES.forEach { type ->
      var rounds = 0
      while (true) {
        val found = search(type, "_project" to project, "_count" to PAGE)
        if (found.isEmpty()) break
        check(++rounds <= MAX_WIPE_ROUNDS) {
          "$type records remain after $MAX_WIPE_ROUNDS wipe rounds"
        }
        found.forEach { expunge("$type/${it.id()}") }
        found.flatMap { it.binaryReferences() }.forEach { expunge(it) }
      }
    }
  }

  /** Searches the project for [type], returning the records as FHIR resources. */
  suspend fun searchResources(type: String, params: Map<String, String>): List<Resource> =
    search(type, "_project" to project(), *params.toList().toTypedArray()).map {
      fhirJson.decodeFromJsonElement(Resource.serializer(), it)
    }

  /**
   * Searches the project for [type], following next links, returning JSON so a rewrite keeps every
   * field.
   */
  suspend fun searchJson(type: String, params: Map<String, String>): List<JsonObject> {
    val found = mutableListOf<JsonObject>()
    var text =
      send(
        HttpMethod.Get,
        "$baseUrl/fhir/R4/$type",
        params = mapOf("_project" to project()) + params,
      )
    while (true) {
      val bundle = fhirJson.parseToJsonElement(text).jsonObject
      found +=
        bundle["entry"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("resource").jsonObject }
      val next =
        bundle["link"]
          ?.jsonArray
          .orEmpty()
          .map { it.jsonObject }
          .firstOrNull { it["relation"]?.jsonPrimitive?.contentOrNull == "next" }
          ?.get("url")
          ?.jsonPrimitive
          ?.contentOrNull ?: break
      text = send(HttpMethod.Get, "$baseUrl/fhir/R4/$type?${URI(next).rawQuery}")
    }
    return found
  }

  /**
   * Replaces [resources] in one transaction, so all change or none; each must be in the project.
   * Medplum never returns `meta.project`, so membership is asked of the server.
   */
  suspend fun replaceAll(resources: List<JsonObject>) {
    val project = project()
    resources
      .groupBy { it.reference().substringBefore('/') }
      .forEach { (type, group) ->
        val ids = group.map { it.reference().substringAfter('/') }
        val inProject =
          search(type, "_project" to project, "_id" to ids.joinToString(","), "_count" to PAGE)
            .mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }
            .toSet()
        ids.forEach { check(it in inProject) { "$type/$it is not in the $PROJECT_NAME project" } }
      }
    val bundle = buildJsonObject {
      put("resourceType", "Bundle")
      put("type", "transaction")
      put(
        "entry",
        JsonArray(
          resources.map {
            buildJsonObject {
              put("resource", it)
              put(
                "request",
                buildJsonObject {
                  put("method", "PUT")
                  put("url", it.reference())
                },
              )
            }
          }
        ),
      )
    }
    send(HttpMethod.Post, "$baseUrl/fhir/R4", bundle.toString())
  }

  /**
   * Upserts the base records by their IG id, pointing references between them at Medplum's ids. The
   * Taifafeki profiles go in with snapshots, so Medplum validates records that name them.
   */
  suspend fun seedBase(ig: List<Resource>) {
    val ids = mutableMapOf<String, String>()
    val snapshots = ProfileSnapshots { url ->
      checkNotNull(search("StructureDefinition", "url" to url).firstOrNull()) {
        "Medplum has no $url"
      }
    }
    val profiles =
      ig
        .filterIsInstance<StructureDefinition>()
        .filter { it.id?.startsWith(PROFILE_PREFIX) == true }
        .map { snapshots.withSnapshot(encode(it)) }
    val base =
      ig.filterIsInstance<CodeSystem>().map(::encode) +
        ig.filterIsInstance<ValueSet>().map(::encode) +
        profiles +
        ig
          .filterIsInstance<Organization>()
          .filter { it.id in listOf(HOSPITAL, CLINIC) }
          .map(::encode) +
        ig.filterIsInstance<Practitioner>().filter { it.id == ANAN }.map(::encode) +
        parse(HOSPITAL_LOCATION) +
        PRACTITIONER_ROLES.map(::parse) +
        ig.filterIsInstance<Device>().filter { it.id == "TrustDemoClinicViewer" }.map(::encode)
    base.forEach { resource ->
      val igId = resource.getValue("id").jsonPrimitive.content
      val type = resource.getValue("resourceType").jsonPrimitive.content
      val linked = relink(resource, ids)
      ids["$type/$igId"] = "$type/${upsert(type, igId, linked, owner(type, linked, ids))}"
    }
  }

  /**
   * Logs Dr Anan in at [ANAN_EMAIL], created once with the clinician policy and kept across runs.
   */
  suspend fun ensureClinician(password: String) {
    val project = project()
    val policy = upsertPolicy(project)
    val anan = checkNotNull(findByIgId("Practitioner", ANAN)) { "seedBase must run first" }.id()
    val access =
      JsonArray(
        listOf(HOSPITAL, CLINIC).map { igId ->
          val organization =
            checkNotNull(findByIgId("Organization", igId)) { "seedBase must run first" }
          buildJsonObject {
            put("policy", reference("AccessPolicy/$policy"))
            put(
              "parameter",
              JsonArray(
                listOf(
                  buildJsonObject {
                    put("name", TENANT_PARAMETER)
                    put("valueReference", reference("Organization/${organization.id()}"))
                  }
                )
              ),
            )
          }
        }
      )
    val member =
      membership(project, anan)
        ?: run {
          val invite = buildJsonObject {
            put("resourceType", "Practitioner")
            put("firstName", "Anan")
            put("lastName", "Kesaro")
            put("email", ANAN_EMAIL)
            put("password", password)
            put("sendEmail", false)
            put("membership", buildJsonObject { put("profile", reference("Practitioner/$anan")) })
          }
          send(HttpMethod.Post, "$baseUrl/admin/projects/$project/invite", invite.toString())
          checkNotNull(membership(project, anan)) { "Medplum did not create Dr Anan's membership" }
        }
    write(
      "ProjectMembership",
      member.id(),
      JsonObject(member - "accessPolicy" + ("access" to access)),
    )
  }

  private suspend fun membership(project: String, practitioner: String): JsonObject? =
    search(
        "ProjectMembership",
        "project" to "Project/$project",
        "profile" to "Practitioner/$practitioner",
      )
      .firstOrNull()

  /** Creates or updates the public client that Dr Anan signs in through, returning its id. */
  suspend fun ensureClientApplication(redirectUri: String): String {
    val application = buildJsonObject {
      put("resourceType", "ClientApplication")
      put("meta", buildJsonObject { put("project", project()) })
      put("name", CLIENT_NAME)
      put("redirectUri", redirectUri)
    }
    return write(
      "ClientApplication",
      search("ClientApplication", "_project" to project(), "name" to CLIENT_NAME)
        .firstOrNull()
        ?.id(),
      application,
    )
  }

  private suspend fun expunge(reference: String) {
    send(
      HttpMethod.Post,
      "$baseUrl/fhir/R4/$reference/\$expunge",
      "{\"resourceType\":\"Parameters\"}",
    )
  }

  private fun JsonElement.binaryReferences(): List<String> =
    when (this) {
      is JsonObject -> values.flatMap { it.binaryReferences() }
      is JsonArray -> flatMap { it.binaryReferences() }
      is JsonPrimitive -> listOfNotNull(contentOrNull?.takeIf { it.startsWith("Binary/") })
    }

  private suspend fun project(): String =
    projectId
      ?: (search("Project", "name" to PROJECT_NAME)
          .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == PROJECT_NAME }
          ?.id()
          ?: create(
            "Project",
            buildJsonObject {
              put("resourceType", "Project")
              put("name", PROJECT_NAME)
            },
          ))
        .also { projectId = it }

  private suspend fun upsertPolicy(project: String): String {
    val policy = buildJsonObject {
      put("resourceType", "AccessPolicy")
      put("meta", buildJsonObject { put("project", project) })
      put("name", POLICY_NAME)
      put(
        "resource",
        JsonArray(
          READ_ONLY.map { rule(it, readonly = true) } +
            READ_WRITE.map { rule(it, readonly = false) }
        ),
      )
    }
    return write(
      "AccessPolicy",
      search("AccessPolicy", "_project" to project, "name" to POLICY_NAME).firstOrNull()?.id(),
      policy,
    )
  }

  private fun rule(type: String, readonly: Boolean) = buildJsonObject {
    put("resourceType", type)
    if (type in IN_COMPARTMENT) put("criteria", "$type?_compartment=%$TENANT_PARAMETER")
    if (readonly) put("readonly", true)
  }

  private suspend fun meta(account: String?) = buildJsonObject {
    put("project", project())
    if (account != null) put("accounts", JsonArray(listOf(reference(account))))
  }

  private fun owner(type: String, resource: JsonObject, ids: Map<String, String>): String? =
    when (type) {
      "Location" -> resource.referenceAt("managingOrganization")
      "PractitionerRole" -> resource.referenceAt("organization")
      "Device" -> ids["Organization/$CLINIC"]
      else -> null
    }

  private fun JsonObject.referenceAt(name: String) =
    this[name]?.jsonObject?.get("reference")?.jsonPrimitive?.contentOrNull

  private suspend fun upsert(
    type: String,
    igId: String,
    resource: JsonObject,
    account: String?,
  ): String {
    val body =
      JsonObject(
        resource - "id" +
          mapOf(
            "meta" to meta(account),
            "identifier" to
              JsonArray(
                resource["identifier"]?.jsonArray.orEmpty() + identifier(IG_ID_SYSTEM, igId)
              ),
          )
      )
    return write(type, findByIgId(type, igId)?.id(), body)
  }

  private suspend fun findByIgId(type: String, igId: String): JsonObject? =
    search(type, "_project" to project(), "identifier" to "$IG_ID_SYSTEM|$igId").firstOrNull()

  private suspend fun write(type: String, existing: String?, body: JsonObject): String =
    if (existing == null) create(type, body)
    else {
      send(
        HttpMethod.Put,
        "$baseUrl/fhir/R4/$type/$existing",
        JsonObject(body + ("id" to JsonPrimitive(existing))).toString(),
      )
      existing
    }

  private suspend fun create(type: String, body: JsonObject): String =
    fhirJson
      .parseToJsonElement(send(HttpMethod.Post, "$baseUrl/fhir/R4/$type", body.toString()))
      .jsonObject
      .id()

  private suspend fun search(type: String, vararg params: Pair<String, String>): List<JsonObject> =
    fhirJson
      .parseToJsonElement(send(HttpMethod.Get, "$baseUrl/fhir/R4/$type", params = params.toMap()))
      .jsonObject["entry"]
      ?.jsonArray
      .orEmpty()
      .map { it.jsonObject.getValue("resource").jsonObject }

  private suspend fun send(
    method: HttpMethod,
    url: String,
    body: String? = null,
    params: Map<String, String> = emptyMap(),
  ): String {
    val bearer = token ?: authenticate().also { token = it }
    val response: HttpResponse =
      reaching(baseUrl) {
        client.request(url) {
          this.method = method
          header(HttpHeaders.Accept, fhirContentType.toString())
          bearerAuth(bearer)
          params.forEach { (name, value) -> parameter(name, value) }
          body?.let { setBody(TextContent(it, fhirContentType)) }
        }
      }
    if (!response.status.isSuccess()) refused(method, url, response.status)
    return response.bodyAsText()
  }

  private suspend fun authenticate(): String {
    val response =
      reaching(baseUrl) {
        client.submitForm(
          "$baseUrl/oauth2/token",
          parameters {
            append("grant_type", "client_credentials")
            append("client_id", clientId)
            append("client_secret", clientSecret)
          },
        )
      }
    if (!response.status.isSuccess()) {
      refused(HttpMethod.Post, "$baseUrl/oauth2/token", response.status)
    }
    return fhirJson
      .parseToJsonElement(response.bodyAsText())
      .jsonObject
      .getValue("access_token")
      .jsonPrimitive
      .content
  }

  private fun encode(resource: Resource): JsonObject =
    fhirJson.encodeToJsonElement(Resource.serializer(), resource).jsonObject

  private fun parse(json: String): JsonObject = fhirJson.parseToJsonElement(json).jsonObject

  private fun relink(element: JsonObject, ids: Map<String, String>): JsonObject =
    relinkElement(element, ids) as JsonObject

  private fun relinkElement(element: JsonElement, ids: Map<String, String>): JsonElement =
    when (element) {
      is JsonObject ->
        JsonObject(
          element.mapValues { (key, value) ->
            val target = (value as? JsonPrimitive)?.contentOrNull?.takeIf { key == "reference" }
            if (target != null) JsonPrimitive(ids[target] ?: target) else relinkElement(value, ids)
          }
        )
      is JsonArray -> JsonArray(element.map { relinkElement(it, ids) })
      else -> element
    }

  private fun identifier(system: String, value: String) = buildJsonObject {
    put("system", system)
    put("value", value)
  }

  private fun reference(target: String) = buildJsonObject { put("reference", target) }

  private fun JsonObject.id() = getValue("id").jsonPrimitive.content

  private fun JsonObject.reference() = "${getValue("resourceType").jsonPrimitive.content}/${id()}"

  private companion object {
    val HOSPITAL_LOCATION =
      """{"resourceType":"Location","id":"TrustDemoKesaroHospitalLoc","name":"Kesaro Provincial Hospital",
        "managingOrganization":{"reference":"Organization/$HOSPITAL"}}"""

    val PRACTITIONER_ROLES =
      listOf(
        role("TrustDemoAnanHospitalRole", HOSPITAL, "facility-emr", "Hospital EMR & Print Station"),
        role("TrustDemoAnanClinicRole", CLINIC, "vhl-viewer", "VHL Viewer"),
      )

    fun role(id: String, organization: String, code: String, display: String) =
      """{"resourceType":"PractitionerRole","id":"$id",
        "practitioner":{"reference":"Practitioner/$ANAN"},
        "organization":{"reference":"Organization/$organization"},
        "code":[{"coding":[{"system":"http://ohs.dev/fhir/CodeSystem/app-role","code":"$code","display":"$display"}]}],
        "specialty":[{"coding":[{"system":"http://snomed.info/sct","code":"394537008","display":"Pediatric specialty"}],"text":"Paediatrics"}]}"""
  }
}
