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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme

/** One labelled value; [mono] for ids, codes and references. */
@Immutable data class Field(val label: String, val value: String, val mono: Boolean = false)

/** Labelled values in one or two [columns], labels in small uppercase monospace. */
@Composable
fun FieldGrid(fields: List<Field>, modifier: Modifier = Modifier, columns: Int = 1) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    fields.chunked(columns).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row.forEach { FieldItem(it, Modifier.weight(1f)) }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

@Composable
private fun FieldItem(field: Field, modifier: Modifier) {
  Column(modifier) {
    Text(field.label.uppercase(), style = OnesaTheme.mono.label, color = OnesaColors.Slate400)
    Text(
      field.value,
      modifier = Modifier.padding(top = 1.dp),
      style =
        if (field.mono) OnesaTheme.mono.value
        else MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 17.sp),
      fontWeight = if (field.mono) FontWeight.Bold else FontWeight.SemiBold,
      color = OnesaColors.Slate800,
    )
  }
}
