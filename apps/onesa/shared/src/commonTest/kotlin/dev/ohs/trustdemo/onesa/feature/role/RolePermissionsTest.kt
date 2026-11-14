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
package dev.ohs.trustdemo.onesa.feature.role

import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.Permission
import dev.ohs.trustdemo.onesa.auth.ROLE_PERMISSIONS
import dev.ohs.trustdemo.onesa.auth.can
import dev.ohs.trustdemo.onesa.feature.home.HomeDestination
import dev.ohs.trustdemo.onesa.feature.home.destinations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RolePermissionsTest {
  @Test
  fun eachRoleSeesOnlyItsActions() {
    assertEquals(AppRole.entries.toSet(), ROLE_PERMISSIONS.keys)
    AppRole.entries.forEach { role ->
      val granted = ROLE_PERMISSIONS.getValue(role)
      assertEquals(
        HomeDestination.entries.filter { it.permission in granted },
        role.destinations(),
        "$role tabs",
      )
      assertEquals(
        ScreenAction.entries.filter { it.permission in granted }.toSet(),
        role.actions(),
        "$role actions",
      )
      Permission.entries.forEach { assertEquals(it in granted, role.can(it), "$role $it") }
    }

    val holders = { permission: Permission ->
      AppRole.entries.filter { it.can(permission) }.toSet()
    }
    assertEquals(setOf(AppRole.CHW), holders(Permission.Register))
    assertEquals(setOf(AppRole.CLINICIAN), holders(Permission.RecordDose))
    assertEquals(setOf(AppRole.CLINICIAN), holders(Permission.Referrals))
    assertEquals(setOf(AppRole.FAMILY), holders(Permission.Wallet))
    assertEquals(setOf(AppRole.CHW, AppRole.CLINICIAN), holders(Permission.TrustLists))
    assertTrue(Permission.entries.none { AppRole.FAMILY.can(it) && it != Permission.Wallet })
    assertEquals(
      setOf(ScreenAction.RegisterHousehold, ScreenAction.AddNewborn),
      AppRole.CHW.actions(),
    )
    assertEquals(setOf(ScreenAction.RecordDose), AppRole.CLINICIAN.actions())
    assertEquals(emptySet(), AppRole.FAMILY.actions())
  }
}
