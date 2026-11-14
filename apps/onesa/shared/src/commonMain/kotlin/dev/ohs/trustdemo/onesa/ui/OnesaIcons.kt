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
package dev.ohs.trustdemo.onesa.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The build map's line icons: a 24-unit grid, 2-unit strokes, round caps and joins. */
object OnesaIcons {
  val Syringe: ImageVector by lazy {
    lineIcon(
      "syringe",
      "M18 2l4 4M15 5l4 4M17 7l-9.5 9.5a2 2 0 0 1-2.8 0l-.2-.2a2 2 0 0 1 0-2.8L14 4M9 11l2 2M6 14l2 2M5 19l-3 3",
    )
  }
  val Scan: ImageVector by lazy {
    lineIcon(
      "scan",
      "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2M7 12h10",
    )
  }
  val Qr: ImageVector by lazy {
    lineIcon(
      "qr",
      "M3,3h7v7h-7z",
      "M14,3h7v7h-7z",
      "M3,14h7v7h-7z",
      "M14 14h3v3h-3zM20 14v.01M14 20h.01M17 20h4v-3",
    )
  }
  val Users: ImageVector by lazy {
    lineIcon(
      "users",
      "M5,8a4,4 0 1,0 8,0a4,4 0 1,0 -8,0",
      "M2 21a7 7 0 0 1 14 0M16 4a4 4 0 0 1 0 8M22 21a7 7 0 0 0-4-6.3",
    )
  }
  val User: ImageVector by lazy {
    lineIcon("user", "M8,8a4,4 0 1,0 8,0a4,4 0 1,0 -8,0", "M4 21a8 8 0 0 1 16 0")
  }
  val Baby: ImageVector by lazy {
    lineIcon(
      "baby",
      "M7.5,8a4.5,4.5 0 1,0 9,0a4.5,4.5 0 1,0 -9,0",
      "M10.2 7.5h.01M13.8 7.5h.01M10.5 9.8a2 2 0 0 0 3 0M6 21v-1.5a6 6 0 0 1 12 0V21",
    )
  }
  val File: ImageVector by lazy {
    lineIcon(
      "file",
      "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M8 13h8M8 17h5",
    )
  }
  val Building: ImageVector by lazy {
    lineIcon(
      "building",
      "M6,2h12a2,2 0 0,1 2,2v16a2,2 0 0,1 -2,2h-12a2,2 0 0,1 -2,-2v-16a2,2 0 0,1 2,-2z",
      "M9 22v-4h6v4M8 6h.01M16 6h.01M12 6h.01M12 10h.01M12 14h.01M16 10h.01M16 14h.01M8 10h.01M8 14h.01",
    )
  }
  val Layers: ImageVector by lazy {
    lineIcon("layers", "M12 2L2 7l10 5 10-5zM2 17l10 5 10-5M2 12l10 5 10-5")
  }
  val Calendar: ImageVector by lazy {
    lineIcon(
      "calendar",
      "M5,4h14a2,2 0 0,1 2,2v14a2,2 0 0,1 -2,2h-14a2,2 0 0,1 -2,-2v-14a2,2 0 0,1 2,-2z",
      "M16 2v4M8 2v4M3 10h18",
    )
  }
  val Clock: ImageVector by lazy {
    lineIcon("clock", "M2,12a10,10 0 1,0 20,0a10,10 0 1,0 -20,0", "M12 6v6l4 2")
  }
  val Globe: ImageVector by lazy {
    lineIcon(
      "globe",
      "M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0",
      "M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18",
    )
  }
  val ShieldCheck: ImageVector by lazy {
    lineIcon("shield-check", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z", "M9 12l2 2 4-4")
  }
  val Alert: ImageVector by lazy { lineIcon("alert", "M12 3L2 21h20zM12 10v4M12 17h.01") }
  val Shield: ImageVector by lazy {
    lineIcon("shield", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z")
  }
  val ShieldX: ImageVector by lazy {
    lineIcon(
      "shield-x",
      "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z",
      "M9.5 9.5l5 5M14.5 9.5l-5 5",
    )
  }
  val Info: ImageVector by lazy {
    lineIcon("info", "M2,12a10,10 0 1,0 20,0a10,10 0 1,0 -20,0", "M12 16v-4M12 8h.01")
  }
  val Lock: ImageVector by lazy {
    lineIcon(
      "lock",
      "M6,11h12a2,2 0 0,1 2,2v7a2,2 0 0,1 -2,2h-12a2,2 0 0,1 -2,-2v-7a2,2 0 0,1 2,-2z",
      "M8 11V7a4 4 0 0 1 8 0v4",
    )
  }
  val Eye: ImageVector by lazy {
    lineIcon(
      "eye",
      "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z",
      "M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0",
    )
  }
  val Send: ImageVector by lazy { lineIcon("send", "M22 2L11 13M22 2l-7 20-4-9-9-4z") }
  val Check: ImageVector by lazy { lineIcon("check", "M20 6L9 17l-5-5") }
}

private fun lineIcon(name: String, vararg paths: String): ImageVector =
  ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .apply {
      paths.forEach {
        addPath(
          pathData = addPathNodes(it),
          stroke = SolidColor(Color.Black),
          strokeLineWidth = 2f,
          strokeLineCap = StrokeCap.Round,
          strokeLineJoin = StrokeJoin.Round,
        )
      }
    }
    .build()
