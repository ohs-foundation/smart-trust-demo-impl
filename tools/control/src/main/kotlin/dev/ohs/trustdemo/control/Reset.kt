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
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.trustdemo.trustkit.ClinicCertificates
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Every AuditEvent but the gateway's read and search audits: its write audits, the apps' scan
 * audits and the VHL service's accesses, so a read or a search never wakes `watch`.
 */
internal const val WATCH_CRITERIA =
  "AuditEvent?action=C,U,D,E&subtype:not=" +
    "http://hl7.org/fhir/restful-interaction|search-type," +
    "http://hl7.org/fhir/restful-interaction|search-system"

/**
 * Puts both countries into the story's starting state: Somsak registered with his birth doses (J1
 * done), or before J1 when [run] is asked to: Onesa's HAPI server and Taifafeki's Medplum project,
 * and in [secretsDir] the clinic certificate and the facility client's id.
 */
class Reset(
  private val onesa: FhirServer,
  private val kesaro: MedplumAdmin,
  private val ananPassword: String,
  private val secretsDir: Path,
  private val watchPort: Int,
  private val clientOrigin: String,
) {
  suspend fun run(today: LocalDate, beforeJ1: Boolean = false) {
    val ig = IgResources.all()
    kesaro.preflight()
    onesa.ping()
    onesa.expungeEverything()
    try {
      onesa.transaction(Seed.onesaBase(ig))
      onesa.transaction(subscription())
      kesaro.wipeStory()
      kesaro.seedBase(ig)
      kesaro.ensureClinician(ananPassword)
      if (!beforeJ1) kesaro.seedJ1(ig, ChronoUnit.DAYS.between(IG_J3, today))
      writeClinicCertificate()
      writeClientId(kesaro.ensureClientApplication(clientOrigin))
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      throw CliktError(
        "The reset stopped after emptying HAPI — run it again (${failure.message})",
        failure,
      )
    }
  }

  private fun subscription(): Bundle {
    val webhook =
      Json.decodeFromString(
        Resource.serializer(),
        """{"resourceType":"Subscription","id":"TrustDemoWatch","status":"requested",
          "reason":"trust-demo watch follows the story as the apps write records",
          "criteria":"$WATCH_CRITERIA",
          "channel":{"type":"rest-hook","endpoint":"http://host.docker.internal:$watchPort/hook"}}""",
      )
    return Bundle(
      type = Enumeration(value = Bundle.BundleType.Transaction),
      entry =
        listOf(
          Bundle.Entry(
            resource = webhook,
            request =
              Bundle.Entry.Request(
                method = Enumeration(value = Bundle.HTTPVerb.Put),
                url = Uri(value = "Subscription/TrustDemoWatch"),
              ),
          )
        ),
    )
  }

  private fun writeClinicCertificate() {
    val (key, certificate) = ClinicCertificates.issueClinicViewer()
    Files.createDirectories(secretsDir)
    writePem(secretsDir.resolve("clinic-viewer.key"), "PRIVATE KEY", key.encoded)
    writePem(secretsDir.resolve("clinic-viewer.crt"), "CERTIFICATE", certificate.encoded)
  }

  private fun writeClientId(id: String) {
    Files.createDirectories(secretsDir)
    Files.writeString(secretsDir.resolve("medplum-client-id"), id)
  }

  private fun writePem(path: Path, label: String, der: ByteArray) {
    val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
    if (!Files.exists(path)) Files.createFile(path)
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    Files.writeString(path, "-----BEGIN $label-----\n$body\n-----END $label-----\n")
  }
}
