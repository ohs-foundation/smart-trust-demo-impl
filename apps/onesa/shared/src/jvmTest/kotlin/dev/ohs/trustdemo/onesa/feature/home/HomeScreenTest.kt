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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.ohs.fhir.engine.sync.FhirDataStore
import dev.ohs.fhir.engine.sync.SyncJobStatus
import dev.ohs.fhir.engine.sync.createDataStore
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.UnsignedInt
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.buildAppViewRegistry
import dev.ohs.trustdemo.onesa.data.di.repositoryModule
import dev.ohs.trustdemo.onesa.data.di.viewModelModule
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.schedule.DoseRecording
import dev.ohs.trustdemo.onesa.data.sync.FakeSyncManager
import dev.ohs.trustdemo.onesa.data.sync.LocalCopy
import dev.ohs.trustdemo.onesa.data.sync.Outbox
import dev.ohs.trustdemo.onesa.data.sync.SyncManager
import dev.ohs.trustdemo.onesa.util.FhirJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class)
class HomeScreenTest {

  private fun newFhirDataStore(): FhirDataStore {
    val path = Files.createTempFile("home-screen-test", ".preferences_pb").toString()
    return FhirDataStore(createDataStore { path })
  }

  private fun startTestKoin(
    syncManager: SyncManager,
    repository: FhirRepository = InMemorySampleFhirRepository(),
  ) {
    startKoin {
      modules(
        module {
          single<FhirRepository> { repository }
          single { newFhirDataStore() }
          single<SyncManager> { syncManager }
          single {
            DoseRecording(
              get(),
              { emptyList() },
              { null },
              HttpClient(MockEngine { error("This test never sends") }),
              "",
              { true },
              { true },
            )
          }
          single { LocalCopy(syncManager) }
          single {
            Outbox(
              createDataStore {
                Files.createTempFile("home-screen-outbox", ".preferences_pb").toString()
              },
              HttpClient(MockEngine { error("This test never sends") }),
              "http://gateway/fhir",
            ) {
              null
            }
          }
        },
        repositoryModule,
        viewModelModule,
      )
    }
  }

  @AfterTest fun tearDown() = stopKoin()

  /** The system back gesture or button, pressed by the test. */
  private class SystemBack : NavigationEventInput() {
    fun press() = dispatchOnBackCompleted()
  }

  private fun ig(name: String): Resource =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      File("../../../ig/resources/$name.json").readText(),
    )

  /** The Sengmany household as the reset seeds it: the two parents. */
  private fun seededHousehold(): FhirRepository {
    val parents = listOf("TrustDemoBounmyPatient", "TrustDemoKhamlaPatient")
    val group = ig("Group-TrustDemoHousehold") as Group
    val members =
      group.member.filter { it.entity.reference?.value?.removePrefix("Patient/") in parents }
    return InMemorySampleFhirRepository().apply {
      runBlocking {
        upsert(group.copy(member = members, quantity = UnsignedInt(value = members.size)))
        parents.forEach { upsert((ig("Patient-$it") as Patient).copy(link = emptyList())) }
      }
    }
  }

  @Composable
  private fun Home(back: SystemBack? = null, onSignOut: () -> Unit = {}) {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    DisposableEffect(dispatcher, back) {
      if (back != null) dispatcher?.addInput(back)
      onDispose { if (back != null) dispatcher?.removeInput(back) }
    }
    CompositionLocalProvider(LocalViewRegistry provides buildAppViewRegistry()) {
      MaterialTheme {
        HomeScreen(
          userName = "Test User",
          role = AppRole.CHW,
          onGroupClick = {},
          onDataCaptureClick = {},
          onAddMembers = {},
          onAddClinicalData = {},
          onSignOut = onSignOut,
        )
      }
    }
  }

  /** Lets the sync that reaching Home starts succeed, then answers a tap with [tapped]. */
  private fun arrivalThen(tapped: suspend () -> SyncJobStatus): suspend () -> SyncJobStatus {
    var arrived = false
    return {
      if (arrived) tapped()
      else {
        arrived = true
        SyncJobStatus.Succeeded()
      }
    }
  }

  private fun ComposeUiTest.waitForText(text: String) =
    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

  private fun ComposeUiTest.openBounmy() {
    waitForText("Sengmany household")
    onAllNodesWithText("Sengmany household").onFirst().performClick()
    waitForText("2 Members")
    onNodeWithText("Bounmy", substring = true).performClick()
    waitForText("Overview")
  }

  @Test
  fun systemBack_closesThePatientThenTheHousehold() = runComposeUiTest {
    startTestKoin(FakeSyncManager { SyncJobStatus.Succeeded() }, seededHousehold())
    val back = SystemBack()
    setContent { Home(back) }
    openBounmy()

    runOnIdle { back.press() }
    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Overview").fetchSemanticsNodes().isEmpty()
    }
    assertTrue(onAllNodesWithText("2 Members").fetchSemanticsNodes().isNotEmpty())

    runOnIdle { back.press() }
    waitForText("Select a household to view details")
  }

  @Test
  fun backArrow_closesThePatientThenTheHousehold() = runComposeUiTest {
    startTestKoin(FakeSyncManager { SyncJobStatus.Succeeded() }, seededHousehold())
    setContent { Home() }
    openBounmy()

    onNodeWithContentDescription("Back").performClick()
    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Overview").fetchSemanticsNodes().isEmpty()
    }
    onNodeWithContentDescription("Back").performClick()
    waitForText("Select a household to view details")
    assertTrue(onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun reachingHome_startsOneSync() = runComposeUiTest {
    val fake = FakeSyncManager { SyncJobStatus.Succeeded() }
    startTestKoin(fake)
    setContent { Home() }

    waitUntil(timeoutMillis = 5_000L) { fake.syncNowCount == 1 }
    onNodeWithText("Sync").performClick()
    waitForText("Sync Now")
    assertEquals(1, fake.syncNowCount)
  }

  @Test
  fun signOut_fromTheAccountButton() = runComposeUiTest {
    startTestKoin(FakeSyncManager { SyncJobStatus.Succeeded() })
    var signedOut = 0
    setContent { Home(onSignOut = { signedOut++ }) }

    onNodeWithContentDescription("Account").performClick()
    waitForText("Sign Out")
    onNodeWithText("Sign Out").performClick()
    runOnIdle { assertEquals(1, signedOut) }
  }

  @Test
  fun homeScreen_defaultsToHouseholdsContentWithDrawerSectionsVisible() = runComposeUiTest {
    startTestKoin(FakeSyncManager { SyncJobStatus.Succeeded() })
    val registry = buildAppViewRegistry()
    setContent {
      CompositionLocalProvider(LocalViewRegistry provides registry) {
        MaterialTheme {
          HomeScreen(
            userName = "Test User",
            role = AppRole.CHW,
            onGroupClick = {},
            onDataCaptureClick = {},
            onAddMembers = {},
            onAddClinicalData = {},
            onSignOut = {},
          )
        }
      }
    }

    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("No households").fetchSemanticsNodes().isNotEmpty()
    }
    assertTrue(onAllNodesWithText("Households").fetchSemanticsNodes().isNotEmpty())
    onNodeWithText("Sync").performClick()
    assertTrue(onAllNodesWithText("Sync Now").fetchSemanticsNodes().isNotEmpty())
  }

  @Test
  fun tappingSyncNow_showsProgressIndicatorWhileSyncPending() = runComposeUiTest {
    val syncStarted = CompletableDeferred<Unit>()
    val releaseSyncResult = CompletableDeferred<SyncJobStatus>()
    startTestKoin(
      FakeSyncManager(
        arrivalThen {
          syncStarted.complete(Unit)
          releaseSyncResult.await()
        }
      )
    )
    val registry = buildAppViewRegistry()
    setContent {
      CompositionLocalProvider(LocalViewRegistry provides registry) {
        MaterialTheme {
          HomeScreen(
            userName = "Test User",
            role = AppRole.CHW,
            onGroupClick = {},
            onDataCaptureClick = {},
            onAddMembers = {},
            onAddClinicalData = {},
            onSignOut = {},
          )
        }
      }
    }

    onNodeWithText("Sync").performClick()
    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Sync Now").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText("Sync Now").performClick()

    waitUntil(timeoutMillis = 5_000L) { syncStarted.isCompleted }
    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithContentDescription("Sync in progress").fetchSemanticsNodes().isNotEmpty()
    }

    releaseSyncResult.complete(SyncJobStatus.Succeeded())

    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithContentDescription("Sync in progress").fetchSemanticsNodes().isEmpty()
    }
  }

  @Test
  fun tappingCancelSync_whileSyncing_callsCancelAndShowsCancelledMessage() = runComposeUiTest {
    val syncStarted = CompletableDeferred<Unit>()
    val releaseSyncResult = CompletableDeferred<SyncJobStatus>()
    val fake =
      FakeSyncManager(
        arrivalThen {
          syncStarted.complete(Unit)
          releaseSyncResult.await()
        }
      )
    startTestKoin(fake)
    val registry = buildAppViewRegistry()
    setContent {
      CompositionLocalProvider(LocalViewRegistry provides registry) {
        MaterialTheme {
          HomeScreen(
            userName = "Test User",
            role = AppRole.CHW,
            onGroupClick = {},
            onDataCaptureClick = {},
            onAddMembers = {},
            onAddClinicalData = {},
            onSignOut = {},
          )
        }
      }
    }

    onNodeWithText("Sync").performClick()
    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Sync Now").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText("Sync Now").performClick()
    waitUntil(timeoutMillis = 5_000L) { syncStarted.isCompleted }
    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Cancel sync").fetchSemanticsNodes().isNotEmpty()
    }

    onNodeWithText("Cancel sync").performClick()
    waitUntil(timeoutMillis = 5_000L) { fake.cancelSyncNowCount > 0 }
    releaseSyncResult.complete(SyncJobStatus.Failed())

    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Sync cancelled.").fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Test
  fun tappingSyncNow_onFailure_showsSnackbarMessage() = runComposeUiTest {
    startTestKoin(FakeSyncManager { SyncJobStatus.Failed() })
    val registry = buildAppViewRegistry()
    setContent {
      CompositionLocalProvider(LocalViewRegistry provides registry) {
        MaterialTheme {
          HomeScreen(
            userName = "Test User",
            role = AppRole.CHW,
            onGroupClick = {},
            onDataCaptureClick = {},
            onAddMembers = {},
            onAddClinicalData = {},
            onSignOut = {},
          )
        }
      }
    }

    onNodeWithText("Sync").performClick()
    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Sync Now").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText("Sync Now").performClick()

    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Sync failed. Please try again.").fetchSemanticsNodes().isNotEmpty()
    }
  }
}
