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

import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.Permission
import dev.ohs.trustdemo.onesa.auth.can
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_households
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_referrals
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_sync
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_trust_lists
import dev.ohs.trustdemo.onesa.generated.resources.home_destination_wallet
import org.jetbrains.compose.resources.StringResource

/**
 * A top-level destination reachable from [HomeScreen]'s tabs, shown to a role holding its
 * [permission]. A new destination is a new enum entry, not a rewrite.
 */
enum class HomeDestination(val label: StringResource, val permission: Permission) {
  Referrals(label = Res.string.home_destination_referrals, permission = Permission.Referrals),
  Households(label = Res.string.home_destination_households, permission = Permission.Households),
  SentReferrals(
    label = Res.string.home_destination_referrals,
    permission = Permission.SentReferrals,
  ),
  TrustLists(label = Res.string.home_destination_trust_lists, permission = Permission.TrustLists),
  Wallet(label = Res.string.home_destination_wallet, permission = Permission.Wallet),
  Sync(label = Res.string.home_destination_sync, permission = Permission.Upload),
}

/** The tabs for this role, in tab order. */
fun AppRole.destinations(): List<HomeDestination> =
  HomeDestination.entries.filter { can(it.permission) }
