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
package dev.ohs.trustdemo.onesa.feature.sync

import dev.ohs.fhir.engine.sync.download.DownloadRequest
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.sync.DownloadProgress
import dev.ohs.trustdemo.onesa.data.sync.DownloadStatus
import dev.ohs.trustdemo.onesa.util.FhirJson
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStepsTest {
  private val chw =
    listOf(
      "Group",
      "Patient",
      "RelatedPerson",
      "Immunization",
      "ServiceRequest",
      "QuestionnaireResponse",
      "Organization",
      "Location",
      "Practitioner",
    )

  @Test
  fun eachPartIsPlacedAgainstTheTypeBeingDownloaded() {
    assertEquals(
      listOf(
        SyncPart.Organization to StepState.Waiting,
        SyncPart.Households to StepState.Done,
        SyncPart.People to StepState.Downloading,
        SyncPart.Records to StepState.Waiting,
        SyncPart.Practitioner to StepState.Waiting,
      ),
      syncSteps(chw, DownloadStatus(type = "RelatedPerson")),
    )
    assertEquals(
      setOf(StepState.Waiting),
      syncSteps(chw, DownloadStatus()).map { it.second }.toSet(),
    )
    assertEquals(
      setOf(StepState.Done),
      syncSteps(chw, DownloadStatus(finished = true)).map { it.second }.toSet(),
    )
    assertEquals(
      listOf(SyncPart.Households, SyncPart.People, SyncPart.Records, SyncPart.Shares),
      syncSteps(
          listOf(
            "Group",
            "Patient",
            "Immunization",
            "ImmunizationRecommendation",
            "Consent",
            "AuditEvent",
          ),
          DownloadStatus(),
        )
        .map { it.first },
    )
  }

  @Test
  fun thePractitionerRowNamesTheSignedInPractitionerOnly() {
    val directory =
      listOf("TrustDemoAnan", "TrustDemoVilay", "TrustDemoChanthason").map {
        FhirJson.instance.decodeFromString(
          Resource.serializer(),
          """{"resourceType":"Practitioner","id":"$it"}""",
        )
      }

    assertEquals(
      listOf("Practitioner/TrustDemoVilay"),
      stepNames(SyncPart.Practitioner, directory, practitionerId = "TrustDemoVilay"),
    )
    assertEquals(emptyList(), stepNames(SyncPart.Practitioner, directory, practitionerId = null))
  }

  @Test
  fun onlyAFirstPageMovesTheDownloadOn() {
    DownloadProgress.started()
    DownloadProgress.requested(DownloadRequest.of("Group?_count=100&_sort=_lastUpdated"))
    assertEquals(DownloadStatus(type = "Group"), DownloadProgress.status.value)
    DownloadProgress.requested(
      DownloadRequest.of("http://gateway.onesa.example/fhir?_getpages=abc&_getpagesoffset=100")
    )
    assertEquals(DownloadStatus(type = "Group"), DownloadProgress.status.value)
    DownloadProgress.requested(null)
    assertEquals(DownloadStatus(finished = true), DownloadProgress.status.value)
  }
}
