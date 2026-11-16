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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameMatchTest {
  @Test
  fun normalisingFoldsCaseAccentsSpacesAndHyphens() {
    assertEquals("jose maria", NameMatch.normalise("  JOSÉ-María "))
    assertEquals("anne marie", NameMatch.normalise("Anne   Marie"))
  }

  @Test
  fun namesEqualAfterNormalisingMatch() {
    val child = PersonName("Sengmany", listOf("Somsak"))
    assertTrue(NameMatch.same(child, PersonName("SENGMANY", listOf("somsak"))))
    assertTrue(
      NameMatch.same(PersonName("Garcia", listOf("José")), PersonName("GARCIA", listOf("Jose")))
    )
    assertTrue(
      NameMatch.same(
        PersonName("Smith", listOf("Anne-Marie")),
        PersonName("Smith", listOf("Anne", "Marie")),
      )
    )
  }

  @Test
  fun differentNamesDoNotMatch() {
    val child = PersonName("Sengmany", listOf("Somsak"))
    assertFalse(NameMatch.same(child, PersonName("Sengmany", listOf("Noy"))))
    assertFalse(NameMatch.same(child, PersonName("Phommachanh", listOf("Somsak"))))
  }

  @Test
  fun aCardNameReadsAsFamilyAndGiven() {
    assertTrue(
      NameMatch.same(PersonName("Sengmany", listOf("Somsak")), NameMatch.ofText("Somsak SENGMANY"))
    )
  }

  @Test
  fun matchingNeedsTheSameBirthDateAndName() {
    val child = Child("Somsak Sengmany", "2026-01-01", PersonName("Sengmany", listOf("Somsak")))
    assertEquals(CardMatch.Holder, NameMatch.match(child, "Somsak SENGMANY", "2026-01-01"))
    assertEquals(CardMatch.NameDiffers, NameMatch.match(child, "Test Child", "2026-01-01"))
    assertEquals(CardMatch.OtherChild, NameMatch.match(child, "Somsak SENGMANY", "2026-01-02"))
    assertEquals(CardMatch.OtherChild, NameMatch.match(child, "Somsak SENGMANY", "2026-01"))
  }
}
