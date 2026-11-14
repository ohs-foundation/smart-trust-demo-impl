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

import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Location
import dev.ohs.fhir.model.r4.Organization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.trustdemo.onesa.data.sync.DownloadStatus
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.sync_part_households
import dev.ohs.trustdemo.onesa.generated.resources.sync_part_organization
import dev.ohs.trustdemo.onesa.generated.resources.sync_part_people
import dev.ohs.trustdemo.onesa.generated.resources.sync_part_practitioner
import dev.ohs.trustdemo.onesa.generated.resources.sync_part_records
import dev.ohs.trustdemo.onesa.generated.resources.sync_part_schedule
import dev.ohs.trustdemo.onesa.generated.resources.sync_part_shares
import org.jetbrains.compose.resources.StringResource

/** A part of the first download as the person sees it, made of the resource [types] it fetches. */
enum class SyncPart(val label: StringResource, val types: Set<String>) {
  Organization(Res.string.sync_part_organization, setOf("Organization", "Location")),
  Households(Res.string.sync_part_households, setOf("Group")),
  People(Res.string.sync_part_people, setOf("Patient", "RelatedPerson")),
  Records(
    Res.string.sync_part_records,
    setOf(
      "Immunization",
      "ImmunizationRecommendation",
      "ServiceRequest",
      "Task",
      "QuestionnaireResponse",
      "Provenance",
      "AuditEvent",
    ),
  ),
  Schedule(Res.string.sync_part_schedule, setOf("ConceptMap", "CodeSystem")),
  Shares(Res.string.sync_part_shares, setOf("Consent")),
  Practitioner(Res.string.sync_part_practitioner, setOf("Practitioner")),
}

enum class StepState {
  Waiting,
  Downloading,
  Done,
}

/**
 * One row of the first-sync screen: a [part], how far it is, and what has arrived ([names],
 * [count]).
 */
data class SyncStep(
  val part: SyncPart,
  val state: StepState,
  val count: Int = 0,
  val names: List<String> = emptyList(),
)

/**
 * The parts a role downloads, in the order the screen lists them, each placed against [status].
 * [order] is the role's sync config: the download fetches its types one after another.
 */
fun syncSteps(order: List<String>, status: DownloadStatus): List<Pair<SyncPart, StepState>> {
  val current = status.type?.let(order::indexOf) ?: -1
  return SyncPart.entries.mapNotNull { part ->
    val positions = order.indices.filter { order[it] in part.types }
    if (positions.isEmpty()) return@mapNotNull null
    val state =
      when {
        status.finished -> StepState.Done
        current < 0 -> StepState.Waiting
        positions.all { it < current } -> StepState.Done
        positions.any { it <= current } -> StepState.Downloading
        else -> StepState.Waiting
      }
    part to state
  }
}

/**
 * What a downloaded record is called on the first-sync screen, or null when only its count shows.
 */
fun syncedName(resource: Resource): String? =
  when (resource) {
    is Organization -> resource.name?.value
    is Location -> resource.name?.value
    is Group -> resource.name?.value
    is Patient ->
      resource.name.firstOrNull()?.let { name ->
        name.text?.value
          ?: listOfNotNull(name.given.firstOrNull()?.value, name.family?.value).joinToString(" ")
      }
    is Practitioner -> resource.id?.let { "Practitioner/$it" }
    else -> null
  }
