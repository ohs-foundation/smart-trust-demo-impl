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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.monthsUntil
import kotlinx.datetime.yearsUntil

/** Who heads a household ([headMemberId]) and each member's v3 RoleCode ([relations]). */
data class HouseholdRoles(
  val headMemberId: String? = null,
  val relations: Map<String, String> = emptyMap(),
)

/**
 * Reads the household's roles from its records: the head is the member the Group's `managingEntity`
 * names, directly or through the RelatedPerson the member's Patient links to; a parent's
 * relationship is the RoleCode on that RelatedPerson; a child is the patient those RelatedPersons
 * relate to. A member none of these name has no role.
 */
fun householdRoles(
  group: Group,
  members: List<Patient>,
  related: List<RelatedPerson>,
): HouseholdRoles {
  val linked =
    members.associate { member ->
      member.id to member.link.mapNotNull { it.other.reference?.value }.toSet()
    }
  val parents =
    members.mapNotNull { member ->
      val person = related.firstOrNull { "RelatedPerson/${it.id}" in linked[member.id].orEmpty() }
      person?.let { member to it }
    }
  val head = group.managingEntity?.reference?.value
  val relations = buildMap {
    parents.forEach { (member, person) ->
      person.relationship
        .flatMap { it.coding }
        .firstOrNull { it.system?.value == ROLE_CODE_SYSTEM }
        ?.code
        ?.value
        ?.let { code -> member.id?.let { put(it, code) } }
    }
    val children = parents.mapNotNull { (_, person) -> person.patient.reference?.value }.toSet()
    members
      .filter { "Patient/${it.id}" in children }
      .forEach { child -> child.id?.let { put(it, "CHILD") } }
  }
  return HouseholdRoles(
    headMemberId =
      members
        .firstOrNull {
          head != null && ("Patient/${it.id}" == head || head in linked[it.id].orEmpty())
        }
        ?.id,
    relations = relations,
  )
}

/**
 * A person's name as the screens write it: given names, then the family name, which records may
 * hold in capitals (`SENGMANY`) and screens show as a name (`Sengmany`).
 */
fun personName(given: String?, family: String?): String =
  listOfNotNull(
      given,
      family?.let {
        if (it.length > 1 && it == it.uppercase())
          it.lowercase().replaceFirstChar(Char::uppercaseChar)
        else it
      },
    )
    .joinToString(" ")

/** [personName] for the first name a Patient or RelatedPerson carries. */
fun HumanName.display(): String =
  text?.value ?: personName(given.firstOrNull()?.value, family?.value)

/**
 * An age as a household list writes it: weeks under eight weeks (`4w`), months under two years
 * (`12m`), years after (`29y`). Null without a readable [birthDate].
 */
fun shortAge(birthDate: String?, today: LocalDate): String? {
  val born =
    birthDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null
  val days = born.daysUntil(today)
  val months = born.monthsUntil(today)
  return when {
    days < 0 -> null
    days < 56 -> "${days / 7}w"
    months < 24 -> "${months}m"
    else -> "${born.yearsUntil(today)}y"
  }
}

private const val ROLE_CODE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-RoleCode"
