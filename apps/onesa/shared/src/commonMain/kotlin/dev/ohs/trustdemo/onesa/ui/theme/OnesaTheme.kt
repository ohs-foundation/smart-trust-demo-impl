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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val LocalMonoType = staticCompositionLocalOf { OnesaMonoType(FontFamily.Monospace) }

private fun colorScheme(brand: Color) =
  lightColorScheme(
    primary = brand,
    onPrimary = Color.White,
    primaryContainer = brand.copy(alpha = 0.12f).compositeOver(Color.White),
    onPrimaryContainer = brand,
    secondary = OnesaColors.Muted,
    onSecondary = Color.White,
    secondaryContainer = OnesaColors.Slate100,
    onSecondaryContainer = OnesaColors.Slate700,
    tertiary = OnesaColors.Ok,
    onTertiary = Color.White,
    tertiaryContainer = Tone.Success.soft,
    onTertiaryContainer = Tone.Success.ink,
    error = OnesaColors.Crimson,
    onError = Color.White,
    errorContainer = Tone.Danger.soft,
    onErrorContainer = Tone.Danger.ink,
    background = OnesaColors.Bg,
    onBackground = OnesaColors.Ink,
    surface = Color.White,
    onSurface = OnesaColors.Ink,
    surfaceVariant = OnesaColors.Slate100,
    onSurfaceVariant = OnesaColors.Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = OnesaColors.Slate100,
    outline = OnesaColors.Slate300,
    outlineVariant = OnesaColors.Line,
  )

private val OnesaShapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
  )

/**
 * The Onesa app's look: the build map's slate surfaces and Plus Jakarta Sans, with [brand] as the
 * accent. Light only, as the build map draws every device screen.
 */
@Composable
fun OnesaTheme(brand: Color = OnesaColors.BrandOnesa, content: @Composable () -> Unit) {
  val sans = plusJakartaSans()
  val mono = jetBrainsMono()
  val typography = remember(sans) { onesaTypography(sans) }
  val monoType = remember(mono) { OnesaMonoType(mono) }
  val colors = remember(brand) { colorScheme(brand) }
  CompositionLocalProvider(LocalMonoType provides monoType) {
    MaterialTheme(
      colorScheme = colors,
      typography = typography,
      shapes = OnesaShapes,
      content = content,
    )
  }
}

/** Access to what [OnesaTheme] adds beside Material's slots. */
object OnesaTheme {
  val mono: OnesaMonoType
    @Composable @ReadOnlyComposable get() = LocalMonoType.current
}
