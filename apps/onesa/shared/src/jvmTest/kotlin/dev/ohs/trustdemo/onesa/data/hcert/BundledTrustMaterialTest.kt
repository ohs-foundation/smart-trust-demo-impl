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
package dev.ohs.trustdemo.onesa.data.hcert

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledTrustMaterialTest {

  @Test
  fun bundledSignersAndSnapshotMatchTheTestkit() {
    val pairs =
      mapOf(
        "local-list-signer.json" to "testkit/gdhcn-signer-did.json",
        "gdhcn-dev-signer.json" to "testkit/who/WHO-signing-did.json",
        "gdhcn-dev-trustlist.json" to "testkit/who/root-did.json",
      )
    pairs.forEach { (bundled, source) ->
      val app = File(BUNDLED, bundled)
      val testkit = File(REPO, source)
      assertTrue(app.length() > 0 && testkit.length() > 0, "$bundled or $source is missing")
      assertEquals(testkit.readText(), app.readText(), "$bundled differs from $source")
    }
  }

  private companion object {
    val REPO = File("../../..")
    val BUNDLED = File("src/commonMain/composeResources/files/trust")
  }
}
