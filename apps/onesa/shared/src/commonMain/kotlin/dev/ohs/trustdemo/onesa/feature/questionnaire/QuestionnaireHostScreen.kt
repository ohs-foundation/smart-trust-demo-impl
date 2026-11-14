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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.fhir.datacapture.Questionnaire
import dev.ohs.fhir.datacapture.QuestionnaireConfig
import dev.ohs.fhir.datacapture.QuestionnaireItemViewFactoryMatcher
import dev.ohs.fhir.datacapture.QuestionnaireItemViewFactoryMatchersProvider
import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.feature.home.RoleTopBar
import dev.ohs.trustdemo.onesa.feature.home.rememberSyncChip
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.questionnaire_back
import dev.ohs.trustdemo.onesa.generated.resources.questionnaire_retry
import dev.ohs.trustdemo.onesa.generated.resources.questionnaire_title
import dev.ohs.trustdemo.onesa.generated.resources.registration_continue
import dev.ohs.trustdemo.onesa.generated.resources.registration_form_subtitle
import dev.ohs.trustdemo.onesa.ui.AlertCard
import dev.ohs.trustdemo.onesa.ui.OnesaIcons
import dev.ohs.trustdemo.onesa.ui.OnesaScaffold
import dev.ohs.trustdemo.onesa.ui.OnesaTopBar
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun QuestionnaireHostScreen(
  questionnaireId: String,
  patientId: String? = null,
  groupId: String? = null,
  onBack: () -> Unit,
  role: AppRole? = null,
) {
  val launchContext =
    remember(patientId, groupId) {
      QuestionnaireLaunchContext(patientId = patientId, groupId = groupId)
    }
  val viewItemMatchersProvider = remember {
    object : QuestionnaireItemViewFactoryMatchersProvider {
      override fun get(): List<QuestionnaireItemViewFactoryMatcher> = listOf()
    }
  }

  val viewModel =
    koinViewModel<QuestionnaireHostViewModel>(key = "$questionnaireId:$patientId:$groupId") {
      parametersOf(questionnaireId, launchContext)
    }
  val uiState by viewModel.uiState.collectAsState()
  val coroutineScope = rememberCoroutineScope()

  LaunchedEffect(uiState) {
    val submitted = uiState as? QuestionnaireHostUiState.Submitted ?: return@LaunchedEffect
    if (submitted.result.registration != null) return@LaunchedEffect
    delay(2_000.milliseconds)
    onBack()
  }
  val roleBar: @Composable (() -> Unit) -> Unit = { back ->
    if (role != null) RoleTopBar(role, back) else FormTopBar(null, questionnaireId, back)
  }

  when (val state = uiState) {
    is QuestionnaireHostUiState.Reviewing -> {
      RegistrationReviewContent(
        review = state.review,
        topBar = { roleBar(viewModel::edit) },
        saving = false,
        onSave = viewModel::confirm,
      )
      return
    }
    is QuestionnaireHostUiState.Submitted ->
      state.result.registration?.let { summary ->
        RegistrationSavedContent(summary, topBar = { roleBar(onBack) }, onDone = onBack)
        return
      }
    else -> Unit
  }

  val title =
    when (val state = uiState) {
      is QuestionnaireHostUiState.Ready -> state.title
      is QuestionnaireHostUiState.Submitting -> state.title
      else -> null
    }
  val newborn = questionnaireId == QuestionnaireIds.NEWBORN_REGISTRATION

  OnesaScaffold(topBar = { FormTopBar(title, questionnaireId, onBack) }, scroll = false) {
    when (val state = uiState) {
      is QuestionnaireHostUiState.Submitted ->
        SubmissionBanner(message = state.result.successMessage)
      else -> Unit
    }

    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      when (val state = uiState) {
        is QuestionnaireHostUiState.Loading -> CircularProgressIndicator()

        is QuestionnaireHostUiState.Error -> {
          Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            AlertCard(title = null, text = state.message, tone = Tone.Danger)
            TextButton(onClick = viewModel::load) {
              Text(stringResource(Res.string.questionnaire_retry))
            }
          }
        }

        is QuestionnaireHostUiState.Ready,
        is QuestionnaireHostUiState.Submitting -> {
          val questionnaireJson =
            when (state) {
              is QuestionnaireHostUiState.Ready -> state.questionnaireJson
              is QuestionnaireHostUiState.Submitting -> state.questionnaireJson
              else -> ""
            }
          Questionnaire(
            questionnaireJson = questionnaireJson,
            questionnaireLaunchContextMap = emptyMap(),
            config =
              QuestionnaireConfig(
                showReviewPage = !newborn,
                showReviewPageFirst = false,
                isReadOnly = false,
                showCancelButton = false,
                submitButtonText =
                  if (newborn) stringResource(Res.string.registration_continue) else null,
              ),
            onSubmit = { getResponse ->
              coroutineScope.launch { viewModel.onSubmit(getResponse()) }
            },
            matchersProvider = viewItemMatchersProvider,
            onCancel = {},
          )
        }

        else -> Unit
      }
    }
  }
}

/** The form's header: its title, that SDC draws it from [questionnaireId], and the sync chip. */
@Composable
private fun FormTopBar(title: String?, questionnaireId: String, onBack: () -> Unit) {
  OnesaTopBar(
    title = title ?: stringResource(Res.string.questionnaire_title),
    icon = OnesaIcons.File,
    subtitle = stringResource(Res.string.registration_form_subtitle, questionnaireId),
    status = rememberSyncChip(),
    onIconClick = onBack,
    iconLabel = stringResource(Res.string.questionnaire_back),
  )
}

@Composable
private fun SubmissionBanner(message: String) {
  AlertCard(title = null, text = message, tone = Tone.Success)
}
