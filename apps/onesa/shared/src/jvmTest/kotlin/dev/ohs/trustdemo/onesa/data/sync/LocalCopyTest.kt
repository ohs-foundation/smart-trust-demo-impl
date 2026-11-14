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
package dev.ohs.trustdemo.onesa.data.sync

import dev.ohs.fhir.engine.sync.SyncJobStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class LocalCopyTest {
  private var status: SyncJobStatus = SyncJobStatus.Succeeded()
  private var failure: Exception? = null
  private val copy =
    LocalCopy(
      FakeSyncManager {
        failure?.let { throw it }
        status
      }
    )

  @Test
  fun aFailedSyncAfterAWriteLeavesTheCopyBehindUntilASyncSucceeds() = runTest {
    status = SyncJobStatus.Failed()

    assertEquals(false, copy.refreshAfterWrite())
    assertEquals(false, copy.caughtUp())
    copy.syncNow()
    assertEquals(false, copy.caughtUp())

    status = SyncJobStatus.Succeeded()
    copy.syncNow()
    assertEquals(true, copy.caughtUp())
  }

  @Test
  fun aSyncThatThrowsIsReportedNotThrown() = runTest {
    failure = IllegalStateException("offline")

    assertEquals(false, copy.refreshAfterWrite())
    assertEquals(false, copy.caughtUp())
  }

  @Test
  fun cancellationIsNotSwallowed() = runTest {
    failure = CancellationException("cancelled")

    assertFailsWith<CancellationException> { copy.refreshAfterWrite() }
  }
}
