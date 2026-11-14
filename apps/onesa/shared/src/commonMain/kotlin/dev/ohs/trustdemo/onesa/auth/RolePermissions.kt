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
package dev.ohs.trustdemo.onesa.auth

/** What a signed-in user can open or do, as the demo's roles table grants it. */
enum class Permission {
  Households,
  Register,
  Referrals,
  RecordDose,
  PatientProfile,
  TrustLists,
  Wallet,
  Upload,
  SentReferrals,
}

/** The one place a role is tied to what it may do; the tabs, the actions and the routes read it. */
val ROLE_PERMISSIONS: Map<AppRole, Set<Permission>> =
  mapOf(
    AppRole.CHW to
      setOf(
        Permission.Households,
        Permission.Register,
        Permission.SentReferrals,
        Permission.PatientProfile,
        Permission.TrustLists,
        Permission.Upload,
      ),
    AppRole.CLINICIAN to
      setOf(
        Permission.Households,
        Permission.Referrals,
        Permission.RecordDose,
        Permission.PatientProfile,
        Permission.TrustLists,
        Permission.Upload,
      ),
    AppRole.FAMILY to setOf(Permission.Wallet),
  )

fun AppRole.can(permission: Permission): Boolean = permission in ROLE_PERMISSIONS.getValue(this)
