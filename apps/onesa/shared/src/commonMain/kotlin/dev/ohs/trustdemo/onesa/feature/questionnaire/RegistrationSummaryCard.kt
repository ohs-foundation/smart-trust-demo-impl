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
package dev.ohs.trustdemo.onesa.feature.questionnaire

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSummary
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.list_stale
import dev.ohs.trustdemo.onesa.generated.resources.registration_born
import dev.ohs.trustdemo.onesa.generated.resources.registration_done
import dev.ohs.trustdemo.onesa.generated.resources.registration_done_title
import dev.ohs.trustdemo.onesa.generated.resources.registration_doses
import dev.ohs.trustdemo.onesa.generated.resources.registration_no_doses
import dev.ohs.trustdemo.onesa.generated.resources.registration_referred
import org.jetbrains.compose.resources.stringResource

/** S3: what the registration did, once the gateway has accepted it. */
@Composable
fun RegistrationSummaryCard(summary: RegistrationSummary, onDone: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      stringResource(Res.string.registration_done_title, summary.childName),
      style = MaterialTheme.typography.headlineSmall,
    )
    Text(stringResource(Res.string.registration_born, summary.birthDate))
    Text(
      stringResource(Res.string.registration_doses),
      style = MaterialTheme.typography.titleMedium,
    )
    if (summary.doses.isEmpty()) Text(stringResource(Res.string.registration_no_doses))
    summary.doses.forEach { Text(it) }
    summary.referredTo?.let { Text(stringResource(Res.string.registration_referred, it)) }
    if (summary.listStale) Text(stringResource(Res.string.list_stale))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(Res.string.registration_done))
    }
  }
}
