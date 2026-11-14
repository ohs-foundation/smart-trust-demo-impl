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
package dev.ohs.trustdemo.control

private val KEYCLOAK_LOGINS = listOf("vilay", "chanthason", "bounmy", "trustdemo-admin")
private const val MEDPLUM_LOGIN = "anan@kesaro.example"

/** How [step] stands: done, in progress, in progress or not yet synced, or not started. */
fun statusLine(state: StoryState, step: Step): String {
  val met = state.metCount(step)
  return when {
    state.isDone(step) -> "done"
    met > 0 -> "in progress"
    step == Step.J2 && StepEngine.currentStep(state) == Step.J2 -> "in progress or not yet synced"
    else -> "not started"
  }
}

/** The current step and who does it, every step's standing, the date shift and the logins. */
fun status(state: StoryState): String = buildString {
  val current = StepEngine.currentStep(state)
  if (current == null) appendLine("All steps are done.")
  else {
    appendLine("Current step: ${current.code} · ${current.title}")
    appendLine("  who:     ${current.who}")
    appendLine("  device:  ${current.device}")
    appendLine("  screens: ${current.screens}")
  }
  appendLine()
  Step.entries.forEach { step ->
    val total = expectations.getValue(step).size
    appendLine(
      "  ${step.code.padEnd(5)} ${statusLine(state, step).padEnd(30)} " +
        "${state.metCount(step)} of $total  ${step.title}"
    )
  }
  incompleteBeforeWritten(state).forEach { (step, later) ->
    appendLine(
      "  ! ${step.code} incomplete although ${later.joinToString(", ") { it.code }} records exist"
    )
  }
  appendLine()
  appendLine(dateShift(state))
  appendLine()
  appendLine("Logins (passwords in stack/.env)")
  appendLine("  Keycloak: ${KEYCLOAK_LOGINS.joinToString(", ")}")
  appendLine("  Medplum:  $MEDPLUM_LOGIN")
}

private fun incompleteBeforeWritten(state: StoryState): List<Pair<Step, List<Step>>> =
  Step.entries.mapNotNull { step ->
    val later = Step.entries.filter { it > step && state.metCount(it) > 0 }
    if (!state.isDone(step) && later.isNotEmpty()) step to later else null
  }

private fun dateShift(state: StoryState) =
  "Dates: the IG's dates shifted by ${state.shiftDays()} days; " +
    "reset on ${state.resetDay}, today ${state.today}"
