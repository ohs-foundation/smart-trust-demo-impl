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
package dev.ohs.trustdemo.onesa.data.verification

import dev.ohs.trustdemo.hcert.DvcFhirMapper

private val FULL_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val SEPARATORS = Regex("""[\s\-‐-―]+""")

/** The text with its accents split off as combining marks (Unicode NFKD). */
internal expect fun decomposed(text: String): String

/** A person's name as the family name and the given names. */
data class PersonName(val family: String?, val given: List<String>)

/** How a scanned card's holder compares with the child the nurse is verifying. */
enum class CardMatch {
  /** The birth date and the name agree. */
  Holder,

  /** The birth date agrees but the name does not: the nurse may override with a reason. */
  NameDiffers,

  /** The birth date differs, so the card is for another child. */
  OtherChild,
}

object NameMatch {
  /** Without accents or case, with whitespace and hyphens collapsed to single spaces. */
  fun normalise(text: String): String =
    decomposed(text)
      .filterNot { it.category == CharCategory.NON_SPACING_MARK }
      .lowercase()
      .replace(SEPARATORS, " ")
      .trim()

  fun same(a: PersonName, b: PersonName): Boolean =
    normalise(a.family.orEmpty()) == normalise(b.family.orEmpty()) &&
      normalise(a.given.joinToString(" ")) == normalise(b.given.joinToString(" "))

  /** A name written on a card, split the way the Immunization's holder name is. */
  fun ofText(text: String): PersonName {
    val name = DvcFhirMapper.humanName(text)
    return PersonName(name.family?.value, name.given.mapNotNull { it.value })
  }

  fun match(child: Child, cardName: String, cardBirthDate: String): CardMatch =
    when {
      !FULL_DATE.matches(cardBirthDate) || child.birthDate != cardBirthDate -> CardMatch.OtherChild
      same(child.personName, ofText(cardName)) -> CardMatch.Holder
      else -> CardMatch.NameDiffers
    }
}
