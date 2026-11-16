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
package dev.ohs.trustdemo.onesa

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.ohs.trustdemo.onesa.data.schedule.BookingOutcome
import dev.ohs.trustdemo.onesa.feature.referral.ScheduleContent
import dev.ohs.trustdemo.onesa.feature.referral.ScheduleUiState
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalTestApi::class)
class BookingNavigationTest {
  private val today = LocalDate.parse("2025-11-25")

  @Test
  fun booking_endsOnHomeWithHomeOnTop() = runComposeUiTest {
    lateinit var navController: NavHostController
    var state by mutableStateOf(ScheduleUiState(busy = false))
    setContent {
      navController = rememberNavController()
      OnesaTheme {
        NavHost(navController, startDestination = "home") {
          composable("home") { Text("Home") }
          composable("verifyCards/{referralId}") { Text("Verify") }
          composable("schedule/{referralId}") {
            ScheduleContent(
              state,
              today,
              emptyList(),
              onBack = {},
              onBook = {},
              onBooked = navController::backToHome,
            )
          }
        }
      }
    }
    runOnIdle {
      navController.navigate("verifyCards/r")
      navController.navigate("schedule/r")
    }
    runOnIdle {
      assertEquals("schedule/{referralId}", navController.currentDestination?.route)
      state = state.copy(booked = BookingOutcome.Booked(LocalDate.parse("2025-12-09"), 3))
    }

    waitUntil(timeoutMillis = 5_000L) { navController.currentDestination?.route == "home" }
    runOnIdle { assertNull(navController.previousBackStackEntry) }
  }
}
