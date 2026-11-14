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
package dev.ohs.trustdemo.onesa.ui.theme

import androidx.compose.ui.graphics.Color
import dev.ohs.trustdemo.onesa.auth.AppRole

/** The build map's tokens: one brand per app or role, slate for text and surfaces. */
object OnesaColors {
  val BrandOnesa = Color(0xFF0F766E)
  val BrandClinic = Color(0xFF0284C7)
  val BrandFamily = Color(0xFF4F46E5)
  val Crimson = Color(0xFFB91C1C)

  val Ink = Color(0xFF0F172A)
  val Muted = Color(0xFF475569)
  val Line = Color(0xFFE2E8F0)
  val Bg = Color(0xFFF8FAFC)
  val Frame = Color(0xFF0B1220)

  val Slate100 = Color(0xFFF1F5F9)
  val Slate300 = Color(0xFFCBD5E1)
  val Slate400 = Color(0xFF94A3B8)
  val Slate500 = Color(0xFF64748B)
  val Slate700 = Color(0xFF334155)
  val Slate800 = Color(0xFF1E293B)
  val Slate950 = Color(0xFF020617)

  val Ok = Color(0xFF047857)
  val OkSoft = Color(0xFFECFDF5)
  val Bad = Crimson
  val BadSoft = Color(0xFFFFF1F2)
  val Info = Color(0xFF0369A1)
  val InfoSoft = Color(0xFFF0F9FF)

  val Reticle = Color(0xFF10B981)
  val ReticleCorner = Color(0xFF34D399)
  val Sky400 = Color(0xFF38BDF8)
  val Sky700 = Color(0xFF0369A1)
  val QrLabelInk = Color(0xFF78350F)
  val VerifiedIcon = Color(0xFF059669)
}

/**
 * A status colour: [soft] fills, [edge] outlines and [ink] writes. The five tones of the build
 * map's chips, alerts and result cards.
 */
enum class Tone(val soft: Color, val edge: Color, val ink: Color) {
  Neutral(OnesaColors.Slate100, OnesaColors.Line, OnesaColors.Slate700),
  Info(OnesaColors.InfoSoft, Color(0xFFBAE6FD), Color(0xFF075985)),
  Success(OnesaColors.OkSoft, Color(0xFFA7F3D0), Color(0xFF065F46)),
  Warning(Color(0xFFFFFBEB), Color(0xFFFCD34D), Color(0xFF92400E)),
  Danger(OnesaColors.BadSoft, Color(0xFFFDA4AF), Color(0xFF9F1239)),
}

/**
 * The accent a signed-in role works in: teal for the CHW, cobalt for the clinic, indigo for the
 * family.
 */
val AppRole.brand: Color
  get() =
    when (this) {
      AppRole.CHW -> OnesaColors.BrandOnesa
      AppRole.CLINICIAN -> OnesaColors.BrandClinic
      AppRole.FAMILY -> OnesaColors.BrandFamily
    }
