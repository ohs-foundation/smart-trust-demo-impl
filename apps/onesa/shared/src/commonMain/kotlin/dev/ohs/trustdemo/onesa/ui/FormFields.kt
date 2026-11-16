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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme

private val InputShape = RoundedCornerShape(12.dp)

/** A form label in small uppercase monospace. */
@Composable
fun FormLabel(text: String, modifier: Modifier = Modifier) {
  Text(text.uppercase(), modifier, style = OnesaTheme.mono.label, color = OnesaColors.Slate500)
}

@Composable
private fun inputStyle(mono: Boolean): TextStyle =
  if (mono) OnesaTheme.mono.code.copy(fontSize = 13.sp, color = OnesaColors.Ink)
  else MaterialTheme.typography.bodyMedium.copy(color = OnesaColors.Ink)

/**
 * A labelled text input on the build map's form style; [placeholder] shows while it is empty, and a
 * [readOnly] field shows a value the form records but the user does not type, such as who gave a
 * dose.
 */
@Composable
fun OnesaTextField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String? = null,
  mono: Boolean = false,
  minLines: Int = 1,
  maxLines: Int = if (minLines > 1) minLines + 2 else 1,
  readOnly: Boolean = false,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    FormLabel(label)
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.fillMaxWidth(),
      readOnly = readOnly,
      textStyle = inputStyle(mono),
      singleLine = maxLines == 1,
      minLines = minLines,
      maxLines = maxLines,
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      decorationBox = { inner ->
        Box(
          Modifier.fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(Color.White, InputShape)
            .border(1.dp, OnesaColors.Slate300, InputShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          if (value.isEmpty() && placeholder != null) {
            Text(placeholder, style = inputStyle(mono).copy(color = OnesaColors.Slate400))
          }
          inner()
        }
      },
    )
  }
}

/**
 * A labelled choice from [options], shown as a box with a caret that opens a menu, or with [onOpen]
 * a picker of its own, such as a date picker.
 */
@Composable
fun OnesaSelectField(
  label: String,
  value: String,
  options: List<String>,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  mono: Boolean = false,
  onOpen: (() -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    FormLabel(label)
    Box {
      Row(
        Modifier.fillMaxWidth()
          .defaultMinSize(minHeight = 48.dp)
          .background(Color.White, InputShape)
          .border(1.dp, OnesaColors.Slate300, InputShape)
          .clickable(role = Role.DropdownList) { if (onOpen != null) onOpen() else expanded = true }
          .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(value, Modifier.weight(1f), style = inputStyle(mono))
        Text("▾", style = MaterialTheme.typography.bodySmall, color = OnesaColors.Slate400)
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEachIndexed { index, option ->
          DropdownMenuItem(
            text = { Text(option, style = inputStyle(mono)) },
            onClick = {
              expanded = false
              onSelect(index)
            },
          )
        }
      }
    }
  }
}

/**
 * One radio ([check] false) or checkbox option with its [label] and a monospace [code], such as a
 * schedule code or a member's relationship.
 */
@Composable
fun OptionRow(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  check: Boolean = false,
  code: String? = null,
) {
  val interaction =
    if (check) Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() })
    else Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)
  Row(
    modifier.fillMaxWidth().heightIn(min = 48.dp).then(interaction).padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(9.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val brand = MaterialTheme.colorScheme.primary
    if (check) {
      val shape = RoundedCornerShape(6.dp)
      Box(
        Modifier.size(18.dp)
          .background(if (selected) brand else Color.White, shape)
          .border(2.dp, if (selected) brand else OnesaColors.Slate400, shape),
        contentAlignment = Alignment.Center,
      ) {
        if (selected) Icon(OnesaIcons.Check, null, Modifier.size(12.dp), tint = Color.White)
      }
    } else {
      Box(
        Modifier.size(16.dp)
          .border(2.dp, if (selected) brand else OnesaColors.Slate400, CircleShape)
          .padding(4.dp)
          .background(if (selected) brand else Color.Transparent, CircleShape)
      )
    }
    Text(label, style = MaterialTheme.typography.bodyMedium, color = OnesaColors.Ink)
    code?.let { Text(it, style = OnesaTheme.mono.code, color = OnesaColors.Slate500) }
  }
}
