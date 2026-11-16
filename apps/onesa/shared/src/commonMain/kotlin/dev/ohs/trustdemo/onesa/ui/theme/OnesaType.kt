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

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.jetbrains_mono_bold
import dev.ohs.trustdemo.onesa.generated.resources.jetbrains_mono_medium
import dev.ohs.trustdemo.onesa.generated.resources.jetbrains_mono_regular
import dev.ohs.trustdemo.onesa.generated.resources.plus_jakarta_sans_bold
import dev.ohs.trustdemo.onesa.generated.resources.plus_jakarta_sans_medium
import dev.ohs.trustdemo.onesa.generated.resources.plus_jakarta_sans_regular
import dev.ohs.trustdemo.onesa.generated.resources.plus_jakarta_sans_semibold
import org.jetbrains.compose.resources.Font

@Composable
internal fun plusJakartaSans(): FontFamily =
  FontFamily(
    Font(Res.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(Res.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(Res.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.plus_jakarta_sans_bold, FontWeight.Bold),
  )

@Composable
internal fun jetBrainsMono(): FontFamily =
  FontFamily(
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(Res.font.jetbrains_mono_bold, FontWeight.Bold),
  )

/** The build map's device-screen type scale, set in Plus Jakarta Sans. */
internal fun onesaTypography(sans: FontFamily): Typography {
  fun style(size: Float, weight: FontWeight, line: Float) =
    TextStyle(fontFamily = sans, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)
  return Typography(
    displaySmall = style(32f, FontWeight.Bold, 36f),
    headlineMedium = style(28f, FontWeight.Bold, 33f),
    headlineSmall = style(24f, FontWeight.Bold, 29f),
    titleLarge = style(22f, FontWeight.Bold, 27f),
    titleMedium = style(16f, FontWeight.Bold, 21f),
    titleSmall = style(14f, FontWeight.Bold, 18f),
    bodyLarge = style(16f, FontWeight.Normal, 25f),
    bodyMedium = style(14f, FontWeight.Normal, 20f),
    bodySmall = style(12f, FontWeight.Normal, 17f),
    labelLarge = style(14f, FontWeight.Bold, 19f),
    labelMedium = style(12f, FontWeight.SemiBold, 16f),
    labelSmall = style(11f, FontWeight.Medium, 15f),
  )
}

/** Monospace styles for codes, ids, chips and field labels. */
@Immutable
class OnesaMonoType(mono: FontFamily) {
  /** A FHIR reference, product code or lot under a row's label. */
  val code = TextStyle(fontFamily = mono, fontSize = 12.sp, lineHeight = 17.sp)

  /** Text inside a status chip. */
  val chip =
    TextStyle(
      fontFamily = mono,
      fontSize = 11.5.sp,
      lineHeight = 16.sp,
      fontWeight = FontWeight.Medium,
    )

  /** An uppercase field or form label. */
  val label =
    TextStyle(
      fontFamily = mono,
      fontSize = 10.5.sp,
      lineHeight = 13.sp,
      fontWeight = FontWeight.Medium,
      letterSpacing = 0.02.em,
    )

  /** A value shown in monospace, such as a key id. */
  val value =
    TextStyle(fontFamily = mono, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)

  /** The caption under the action bar. */
  val caption = TextStyle(fontFamily = mono, fontSize = 12.sp, lineHeight = 17.sp)

  /** A verdict such as PASS or FAIL. */
  val verdict =
    TextStyle(
      fontFamily = mono,
      fontSize = 11.5.sp,
      lineHeight = 17.sp,
      fontWeight = FontWeight.Bold,
    )

  /** One passcode digit. */
  val digit =
    TextStyle(fontFamily = mono, fontSize = 24.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
}
