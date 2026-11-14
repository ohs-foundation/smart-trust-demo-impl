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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme

/**
 * A passcode in digit boxes under a lock [title], hidden as dots unless [shown]. With
 * [onValueChange] it takes digits from the keyboard, up to [length]; without, it only shows
 * [value].
 */
@Composable
fun PasscodeCard(
  title: String,
  value: String,
  shown: Boolean,
  onShownChange: (Boolean) -> Unit,
  shownLabel: String,
  hiddenLabel: String,
  modifier: Modifier = Modifier,
  length: Int = 4,
  note: String? = null,
  onValueChange: ((String) -> Unit)? = null,
) {
  SectionCard(modifier, title = title, icon = OnesaIcons.Lock, footnote = note) {
    Row(
      Modifier.clickable(role = Role.Switch) { onShownChange(!shown) }
        .padding(horizontal = 4.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(OnesaIcons.Eye, null, tint = OnesaColors.Slate500, modifier = Modifier.size(14.dp))
      Text(
        if (shown) shownLabel else hiddenLabel,
        style = MaterialTheme.typography.labelSmall,
        color = OnesaColors.Slate500,
      )
    }
    val digits: @Composable () -> Unit = {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      ) {
        repeat(length) { index -> Digit(value.getOrNull(index), shown) }
      }
    }
    if (onValueChange == null) {
      digits()
    } else {
      BasicTextField(
        value = value,
        onValueChange = { typed -> onValueChange(typed.filter { it.isDigit() }.take(length)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        decorationBox = { digits() },
      )
    }
  }
}

@Composable
private fun Digit(digit: Char?, shown: Boolean) {
  val shape = RoundedCornerShape(10.dp)
  Box(
    Modifier.size(width = 44.dp, height = 52.dp)
      .background(OnesaColors.Slate100, shape)
      .border(1.dp, OnesaColors.Line, shape),
    contentAlignment = Alignment.Center,
  ) {
    when {
      digit == null -> Unit
      shown -> Text(digit.toString(), style = OnesaTheme.mono.digit, color = OnesaColors.Ink)
      else ->
        Text(
          "•",
          style = OnesaTheme.mono.digit.copy(fontSize = 26.sp),
          color = OnesaColors.Slate500,
        )
    }
  }
}
