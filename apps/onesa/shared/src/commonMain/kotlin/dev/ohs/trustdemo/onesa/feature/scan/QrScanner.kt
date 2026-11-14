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
package dev.ohs.trustdemo.onesa.feature.scan

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.scan_camera_unavailable
import dev.ohs.trustdemo.onesa.generated.resources.scan_point_camera
import dev.ohs.trustdemo.onesa.generated.resources.scan_with_camera
import dev.ohs.trustdemo.onesa.ui.CameraPanel
import org.jetbrains.compose.resources.stringResource

/** What the camera gave back. [Text] is the raw QR content, an `HC1:` string for a card. */
sealed interface QrScan {
  data class Text(val value: String) : QrScan

  data class Failed(val message: String) : QrScan

  data object Cancelled : QrScan
}

/** Whether this build can open a camera; when false the screen offers paste only. */
expect val cameraScanningAvailable: Boolean

/** The live camera QR reader. Desktop shows a notice and leaves the paste field to do the job. */
@Composable expect fun QrScannerView(modifier: Modifier = Modifier, onResult: (QrScan) -> Unit)

/**
 * The viewfinder block for reading a card. Where this build has a camera, "Scan with camera" opens
 * it as a mode that a read card, its close button or Back ends; without one, it points to the paste
 * field.
 */
@Composable
fun CardCameraPanel(
  onResult: (QrScan) -> Unit,
  modifier: Modifier = Modifier,
  caption: String? = null,
) {
  CameraPanel(
    label =
      stringResource(
        if (cameraScanningAvailable) Res.string.scan_point_camera
        else Res.string.scan_camera_unavailable
      ),
    modifier = modifier,
    caption = caption,
    openCameraLabel = stringResource(Res.string.scan_with_camera),
    camera =
      if (cameraScanningAvailable) {
        { close ->
          QrScannerView(Modifier.fillMaxSize()) { scan ->
            close()
            onResult(scan)
          }
        }
      } else null,
  )
}
