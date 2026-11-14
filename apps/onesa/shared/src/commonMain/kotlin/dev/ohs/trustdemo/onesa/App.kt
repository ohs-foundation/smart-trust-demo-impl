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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.auth.AuthState
import dev.ohs.trustdemo.onesa.auth.AuthViewModel
import dev.ohs.trustdemo.onesa.auth.Permission
import dev.ohs.trustdemo.onesa.auth.can
import dev.ohs.trustdemo.onesa.auth.rememberAuthorizationLauncher
import dev.ohs.trustdemo.onesa.data.sharing.ShareService
import dev.ohs.trustdemo.onesa.feature.group.profile.GroupProfileScreen
import dev.ohs.trustdemo.onesa.feature.home.HomeScreen
import dev.ohs.trustdemo.onesa.feature.login.LoginScreen
import dev.ohs.trustdemo.onesa.feature.patient.profile.PatientProfileScreen
import dev.ohs.trustdemo.onesa.feature.questionnaire.QuestionnaireHostScreen
import dev.ohs.trustdemo.onesa.feature.questionnaire.QuestionnaireIds
import dev.ohs.trustdemo.onesa.feature.referral.ScheduleScreen
import dev.ohs.trustdemo.onesa.feature.referral.VerifyCardsScreen
import dev.ohs.trustdemo.onesa.feature.role.NoRoleScreen
import dev.ohs.trustdemo.onesa.feature.role.UserContextState
import dev.ohs.trustdemo.onesa.feature.role.UserContextViewModel
import dev.ohs.trustdemo.onesa.feature.scan.ScanCardScreen
import dev.ohs.trustdemo.onesa.feature.sync.InitialSyncGateState
import dev.ohs.trustdemo.onesa.feature.sync.InitialSyncScreen
import dev.ohs.trustdemo.onesa.feature.sync.InitialSyncViewModel
import dev.ohs.trustdemo.onesa.feature.sync.SyncStepsViewModel
import dev.ohs.trustdemo.onesa.feature.wallet.IssuedLinksScreen
import dev.ohs.trustdemo.onesa.feature.wallet.ShareScreen
import dev.ohs.trustdemo.onesa.feature.wallet.SharedLinksScreen
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import dev.ohs.trustdemo.onesa.ui.theme.brand
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun App() {
  val registry = remember { buildAppViewRegistry() }

  CompositionLocalProvider(LocalViewRegistry provides registry) {
    OnesaTheme {
      val authViewModel: AuthViewModel = koinViewModel()
      val launcher = rememberAuthorizationLauncher()
      val authState by authViewModel.state.collectAsStateWithLifecycle()
      val signingIn by authViewModel.signingIn.collectAsStateWithLifecycle()
      val authError by authViewModel.error.collectAsStateWithLifecycle()

      val shares: ShareService = koinInject()
      LaunchedEffect(Unit) {
        authViewModel.bootstrap()
        shares.purgeExpired()
      }

      when (authState) {
        is AuthState.Loading ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        is AuthState.Unauthenticated ->
          LoginScreen(
            signingIn = signingIn,
            error = authError,
            onSignIn = { authViewModel.login(launcher) },
            onErrorDismiss = { authViewModel.clearError() },
          )
        is AuthState.Authenticated -> {
          val session = (authState as AuthState.Authenticated).session
          val userName =
            session.user.fullName.ifBlank { session.user.username }.ifBlank { session.user.email }
          val contextViewModel: UserContextViewModel = koinViewModel(key = session.user.subject)
          val contextState by contextViewModel.state.collectAsStateWithLifecycle()
          LaunchedEffect(session.accessToken) { contextViewModel.resolve() }

          when (val state = contextState) {
            UserContextState.Loading ->
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
              }
            UserContextState.NoRole ->
              NoRoleScreen(
                message = null,
                onRetry = { contextViewModel.resolve() },
                onSignOut = { authViewModel.logout() },
              )
            is UserContextState.Failed ->
              NoRoleScreen(
                message = state.message,
                onRetry = { contextViewModel.resolve() },
                onSignOut = { authViewModel.logout() },
              )
            is UserContextState.Ready ->
              OnesaTheme(brand = state.context.role.brand) {
                SignedInApp(
                  userName = userName,
                  role = state.context.role,
                  onSignOut = { authViewModel.logout() },
                )
              }
          }
        }
      }
    }
  }
}

@Composable
private fun SignedInApp(userName: String, role: AppRole, onSignOut: () -> Unit) {
  val initialSyncViewModel: InitialSyncViewModel = koinViewModel()
  val gateState by initialSyncViewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { initialSyncViewModel.start() }

  when (gateState) {
    InitialSyncGateState.Checking,
    InitialSyncGateState.Syncing,
    is InitialSyncGateState.Failed -> {
      val stepsViewModel: SyncStepsViewModel = koinViewModel()
      val progress by stepsViewModel.state.collectAsStateWithLifecycle()
      OnesaTheme {
        InitialSyncScreen(
          state = gateState,
          onRetry = { initialSyncViewModel.retry() },
          onContinueAnyway = { initialSyncViewModel.continueAnyway() },
          progress = progress,
          userName = userName,
          role = role,
        )
      }
    }
    InitialSyncGateState.Passed -> {
      val navController = rememberNavController()
      NavHost(navController = navController, startDestination = "home") {

        // Screen 1: Home (the role's header and tabs)
        composable("home") {
          HomeScreen(
            userName = userName,
            role = role,
            onGroupClick = { id -> navController.navigate("groupProfile/$id") },
            onDataCaptureClick = {
              navController.navigate(
                questionnaireHostRoute(questionnaireId = QuestionnaireIds.HOUSEHOLD_REGISTRATION)
              )
            },
            onAddMembers = { groupId -> navController.navigate("scanCard/$groupId") },
            onRegisterNewborn = { groupId ->
              navController.navigate(
                questionnaireHostRoute(
                  questionnaireId = QuestionnaireIds.NEWBORN_REGISTRATION,
                  groupId = groupId,
                )
              )
            },
            onAddClinicalData = { patientId ->
              navController.navigate(
                questionnaireHostRoute(
                  questionnaireId = QuestionnaireIds.PATIENT_CLINICAL_DATA,
                  patientId = patientId,
                )
              )
            },
            onSignOut = onSignOut,
            onOpenReferral = { id -> navController.navigate("verifyCards/$id") },
            onShareRecords = { navController.navigate("share") },
            onSharedLinks = { navController.navigate("shared") },
          )
        }

        composable(
          route = "questionnaireHost/{questionnaireId}?patientId={patientId}&groupId={groupId}",
          arguments =
            listOf(
              navArgument("questionnaireId") { type = NavType.StringType },
              navArgument("patientId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
              },
              navArgument("groupId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
              },
            ),
        ) { back ->
          val questionnaireId =
            back.arguments?.read { getStringOrNull("questionnaireId") }.orEmpty()
          val patientId = back.arguments?.read { getStringOrNull("patientId") }
          val groupId = back.arguments?.read { getStringOrNull("groupId") }
          RouteGuard(role, questionnairePermission(questionnaireId), onSignOut) {
            QuestionnaireHostScreen(
              questionnaireId = questionnaireId,
              patientId = patientId,
              groupId = groupId,
              onBack = { navController.popBackStack() },
              role = role,
            )
          }
        }

        // Screen 2: Household profile (head + members)
        composable(
          route = "groupProfile/{groupId}",
          arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { back ->
          val groupId = back.arguments?.read { getStringOrNull("groupId") }.orEmpty()
          RouteGuard(role, Permission.Households, onSignOut) {
            GroupProfileScreen(
              groupId = groupId,
              role = role,
              onBack = { navController.popBackStack() },
              onMemberClick = { id -> navController.navigate("patientProfile/$id") },
              onAddMembers = { navController.navigate("scanCard/$groupId") },
              onRegisterNewborn = {
                navController.navigate(
                  questionnaireHostRoute(
                    questionnaireId = QuestionnaireIds.NEWBORN_REGISTRATION,
                    groupId = groupId,
                  )
                )
              },
            )
          }
        }

        composable("share") {
          RouteGuard(role, Permission.Wallet, onSignOut) {
            ShareScreen(
              onBack = { navController.popBackStack() },
              onIssued = { ids ->
                navController.navigate("issued/${ids.joinToString(",")}") {
                  popUpTo("share") { inclusive = true }
                }
              },
            )
          }
        }

        composable("shared") {
          RouteGuard(role, Permission.Wallet, onSignOut) {
            SharedLinksScreen(
              onBack = { navController.popBackStack() },
              onShowQr = { id -> navController.navigate("issued/$id") },
            )
          }
        }

        composable(
          route = "issued/{consentIds}",
          arguments = listOf(navArgument("consentIds") { type = NavType.StringType }),
        ) { back ->
          val ids =
            back.arguments
              ?.read { getStringOrNull("consentIds") }
              .orEmpty()
              .split(',')
              .filter { it.isNotBlank() }
          RouteGuard(role, Permission.Wallet, onSignOut) {
            IssuedLinksScreen(consentIds = ids, onBack = { navController.popBackStack() })
          }
        }

        composable(
          route = "verifyCards/{referralId}",
          arguments = listOf(navArgument("referralId") { type = NavType.StringType }),
        ) { back ->
          val referralId = back.arguments?.read { getStringOrNull("referralId") }.orEmpty()
          RouteGuard(role, Permission.Referrals, onSignOut) {
            VerifyCardsScreen(
              referralId = referralId,
              onBack = { navController.popBackStack() },
              onOpenSchedule = { navController.navigate("schedule/$referralId") },
            )
          }
        }

        composable(
          route = "schedule/{referralId}",
          arguments = listOf(navArgument("referralId") { type = NavType.StringType }),
        ) { back ->
          val referralId = back.arguments?.read { getStringOrNull("referralId") }.orEmpty()
          RouteGuard(role, Permission.Referrals, onSignOut) {
            ScheduleScreen(referralId = referralId, onBack = { navController.popBackStack() })
          }
        }

        composable(
          route = "scanCard/{groupId}",
          arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { back ->
          val groupId = back.arguments?.read { getStringOrNull("groupId") }.orEmpty()
          RouteGuard(role, Permission.Register, onSignOut) {
            ScanCardScreen(
              onBack = { navController.popBackStack() },
              onContinue = {
                navController.navigate(
                  questionnaireHostRoute(
                    questionnaireId = QuestionnaireIds.NEWBORN_REGISTRATION,
                    groupId = groupId,
                  )
                )
              },
            )
          }
        }

        // Screen 3: Patient IPS summary
        composable(
          route = "patientProfile/{patientId}",
          arguments = listOf(navArgument("patientId") { type = NavType.StringType }),
        ) { back ->
          val patientId = back.arguments?.read { getStringOrNull("patientId") }.orEmpty()
          RouteGuard(role, Permission.PatientProfile, onSignOut) {
            PatientProfileScreen(
              patientId = patientId,
              role = role,
              onBack = { navController.popBackStack() },
              onAddClinicalData = {
                navController.navigate(
                  questionnaireHostRoute(
                    questionnaireId = QuestionnaireIds.PATIENT_CLINICAL_DATA,
                    patientId = patientId,
                  )
                )
              },
            )
          }
        }
      }
    }
  }
}

/** The permission that opens a form; a form the app does not know opens for no one. */
private fun questionnairePermission(questionnaireId: String): Permission? =
  when (questionnaireId) {
    QuestionnaireIds.HOUSEHOLD_REGISTRATION,
    QuestionnaireIds.HOUSEHOLD_MEMBERS,
    QuestionnaireIds.NEWBORN_REGISTRATION -> Permission.Register
    QuestionnaireIds.PATIENT_CLINICAL_DATA -> Permission.RecordDose
    else -> null
  }

/** Opens [content] for a role holding [permission]; any other role gets the no-role screen. */
@Composable
private fun RouteGuard(
  role: AppRole,
  permission: Permission?,
  onSignOut: () -> Unit,
  content: @Composable () -> Unit,
) {
  if (permission != null && role.can(permission)) {
    content()
  } else {
    NoRoleScreen(message = null, onRetry = null, onSignOut = onSignOut)
  }
}

private fun questionnaireHostRoute(
  questionnaireId: String,
  patientId: String? = null,
  groupId: String? = null,
): String = buildString {
  append("questionnaireHost/$questionnaireId")
  val queryParameters =
    listOfNotNull(patientId?.let { "patientId=$it" }, groupId?.let { "groupId=$it" })
  if (queryParameters.isNotEmpty()) {
    append("?")
    append(queryParameters.joinToString("&"))
  }
}

@OptIn(ExperimentalUuidApi::class) fun generateId(): String = Uuid.random().toString()
