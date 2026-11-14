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
package dev.ohs.trustdemo.control

import io.ktor.client.request.post
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeStates(private vararg val fixtures: String) : StoryStates {
  private var reads = 0

  override suspend fun state() =
    fixtureState("steps/${fixtures[reads++.coerceAtMost(fixtures.lastIndex)]}.json")
}

class WatchTest {
  @Test
  fun aDeliveryReprintsTheStep() = testApplication {
    val printed = mutableListOf<String>()
    val watch = Watch(port = 0, FakeStates("before-J40", "before-J41"), printed::add)
    application { watch.install(this) }
    client.post("/hook")
    client.post("/hook")
    assertEquals(2, printed.size)
    assertTrue("J4.0" in printed[0], printed[0])
    assertTrue("J4.1" in printed[1], printed[1])
  }
}
