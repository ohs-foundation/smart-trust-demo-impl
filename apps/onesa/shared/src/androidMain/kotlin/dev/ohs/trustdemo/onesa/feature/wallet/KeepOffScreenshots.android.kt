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
package dev.ohs.trustdemo.onesa.feature.wallet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun KeepOffScreenshots() {
  val activity = LocalContext.current.activity()
  DisposableEffect(activity) {
    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
  }
}

private tailrec fun Context.activity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
  }
