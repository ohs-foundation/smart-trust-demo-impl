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

import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.trustkit.CardSigner
import dev.ohs.trustdemo.trustkit.ClinicCertificates
import dev.ohs.trustdemo.trustkit.StoryClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate
import java.util.Base64

private const val SOMSAK = "TrustDemoTaifafekiSomsak"
private val J1_DOSES = listOf("TrustDemoJ1HepB", "TrustDemoJ1Bcg")
private val J3_IN_IG = LocalDate.parse("2025-11-25")

/**
 * Puts both countries into the story's starting state, before journey J1: Onesa's HAPI server and
 * Taifafeki's Medplum project, with Somsak's signed cards uploaded and the clinic certificate in
 * [secretsDir].
 */
class Reset(
  private val onesa: FhirServer,
  private val kesaro: MedplumAdmin,
  private val ananPassword: String,
  private val secretsDir: Path,
) {
  suspend fun run(today: LocalDate) {
    val ig = IgResources.all()
    onesa.expungeEverything()
    onesa.transaction(Seed.onesaBase(ig))
    kesaro.wipeStory()
    kesaro.seedBase(ig)
    kesaro.ensureClinician(ananPassword)
    uploadCards(ig, StoryClock(J3_IN_IG, today))
    writeClinicCertificate()
  }

  private suspend fun uploadCards(ig: List<Resource>, clock: StoryClock) {
    val somsak = ig.filterIsInstance<Patient>().single { it.id == SOMSAK }
    val number = somsak.identifier.single()
    J1_DOSES.forEach { id ->
      val dose = ig.filterIsInstance<Immunization>().single { it.id == id }
      kesaro.uploadCard(
        number.system!!.value!!,
        number.value!!.value!!,
        id,
        CardSigner.sign(dose, somsak, clock),
      )
    }
  }

  private fun writeClinicCertificate() {
    val (key, certificate) = ClinicCertificates.issueClinicViewer()
    Files.createDirectories(secretsDir)
    writePem(secretsDir.resolve("clinic-viewer.key"), "PRIVATE KEY", key.encoded)
    writePem(secretsDir.resolve("clinic-viewer.crt"), "CERTIFICATE", certificate.encoded)
  }

  private fun writePem(path: Path, label: String, der: ByteArray) {
    val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
    Files.deleteIfExists(path)
    Files.createFile(
      path,
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
    )
    Files.writeString(path, "-----BEGIN $label-----\n$body\n-----END $label-----\n")
  }
}
