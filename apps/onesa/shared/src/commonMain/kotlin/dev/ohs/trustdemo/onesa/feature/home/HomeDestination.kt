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
package dev.ohs.trustdemo.onesa.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.Permission
import dev.ohs.trustdemo.onesa.auth.can
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_households
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_referrals
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_trust_lists
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_wallet
import org.jetbrains.compose.resources.StringResource

/**
 * A top-level destination reachable from [HomeScreen]'s navigation drawer, shown to a role holding
 * its [permission]. A new destination is a new enum entry, not a rewrite.
 */
enum class HomeDestination(
  val label: StringResource,
  val icon: ImageVector,
  val permission: Permission,
) {
  Referrals(
    label = Res.string.home_destination_referrals,
    icon = Icons.Filled.Email,
    permission = Permission.Referrals,
  ),
  Households(
    label = Res.string.home_destination_households,
    icon = Icons.Filled.Home,
    permission = Permission.Households,
  ),
  TrustLists(
    label = Res.string.home_destination_trust_lists,
    icon = Icons.Filled.Lock,
    permission = Permission.TrustLists,
  ),
  Wallet(
    label = Res.string.home_destination_wallet,
    icon = Icons.Filled.Person,
    permission = Permission.Wallet,
  ),
}

/** The drawer's destinations for this role, in drawer order. */
fun AppRole.destinations(): List<HomeDestination> =
  HomeDestination.entries.filter { can(it.permission) }
