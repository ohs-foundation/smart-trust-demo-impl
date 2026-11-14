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
package dev.ohs.trustdemo.onesa.data.repository

import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.RelatedPerson
import dev.ohs.trustdemo.onesa.data.Extraction.extractor
import dev.ohs.trustdemo.onesa.data.datasource.groupListSearchResults
import dev.ohs.trustdemo.onesa.data.datasource.groupProfileSearchResult
import dev.ohs.trustdemo.onesa.feature.group.profile.GroupProfileUiState
import dev.ohs.trustdemo.onesa.generated.state.GroupHeaderState
import dev.ohs.trustdemo.onesa.generated.state.GroupListState
import dev.ohs.trustdemo.onesa.generated.state.GroupMemberState
import dev.ohs.trustdemo.onesa.util.idOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class GroupRepository(private val fhirRepository: FhirRepository) {
  // FhirPathEvaluator is not concurrent-safe. limitedParallelism(1) serializes all extraction on a
  // single background thread without any explicit locking.
  private val extractorDispatcher = Dispatchers.Default.limitedParallelism(1)

  fun observeGroups(): Flow<List<GroupListState>> = fhirRepository.revision.map { getGroups() }

  suspend fun getGroups(): List<GroupListState> =
    withContext(extractorDispatcher) {
        groupListSearchResults(fhirRepository).flatMap { extractor.extract<GroupListState>(it) }
      }
      .map { group ->
        val head = group.headReference?.let { personNamed(it) } ?: return@map group
        group.copy(
          headGivenName = head.given.firstOrNull()?.value,
          headFamilyName = head.family?.value,
        )
      }

  /** The profiles the households declare, by name: `TrustDemoOnesaHousehold`. */
  suspend fun householdProfiles(): List<String> =
    fhirRepository
      .all("Group")
      .filterIsInstance<Group>()
      .flatMap { group ->
        group.meta?.profile.orEmpty().mapNotNull { it.value?.substringAfterLast('/') }
      }
      .distinct()

  fun observeGroupProfile(groupId: String): Flow<GroupProfileUiState> =
    fhirRepository.revision.map { getGroupProfile(groupId) }

  suspend fun getGroupProfile(groupId: String): GroupProfileUiState =
    withContext(extractorDispatcher) {
      val result =
        groupProfileSearchResult(groupId, fhirRepository)
          ?: return@withContext GroupProfileUiState()
      val profile =
        buildGroupProfileUiState(
          groupHeader = extractor.extract<GroupHeaderState>(result).firstOrNull(),
          extractedMembers = extractor.extract<GroupMemberState>(result),
        )
      val patients = result.included?.get("member").orEmpty().filterIsInstance<Patient>()
      val related =
        patients
          .flatMap { patient -> patient.link.mapNotNull { it.other.idOf("RelatedPerson") } }
          .mapNotNull { fhirRepository.get("RelatedPerson", it) as? RelatedPerson }
      val roles = householdRoles(result.resource as Group, patients, related)
      profile.copy(
        members =
          profile.members.map { member ->
            roles.relations[member.memberId]?.let { member.copy(relationshipCode = it) } ?: member
          },
        headMemberId = roles.headMemberId,
      )
    }

  private suspend fun personNamed(reference: String): HumanName? {
    val type = reference.substringBefore('/')
    val id = reference.substringAfter('/')
    return when (val resource = fhirRepository.get(type, id)) {
      is RelatedPerson -> resource.name.firstOrNull()
      is Patient -> resource.name.firstOrNull()
      else -> null
    }
  }
}

internal fun buildGroupProfileUiState(
  groupHeader: GroupHeaderState?,
  extractedMembers: List<GroupMemberState>,
): GroupProfileUiState {
  if (extractedMembers.isEmpty()) {
    return GroupProfileUiState(groupHeader = groupHeader)
  }

  val headIndex = extractedMembers.indexOfFirst { it.relationshipCode.isNullOrBlank() }
  val resolvedHeadIndex = if (headIndex >= 0) headIndex else 0
  val head = extractedMembers[resolvedHeadIndex]

  return GroupProfileUiState(
    groupHeader =
      (groupHeader ?: GroupHeaderState()).copy(
        headGivenName = head.memberGivenName,
        headFamilyName = head.memberFamilyName,
      ),
    members = extractedMembers,
  )
}
