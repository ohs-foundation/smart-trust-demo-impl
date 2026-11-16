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
  appendLine(currentStep(state))
  appendLine()
  Step.entries.forEach { step ->
    val total = state.expectations.getValue(step).size
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
  if (state.newborn.isSomsak) {
    appendLine(dateShift(state))
    appendLine()
  }
  appendLine("Logins (passwords in stack/.env)")
  appendLine("  Keycloak: ${KEYCLOAK_LOGINS.joinToString(", ")}")
  appendLine("  Medplum:  $MEDPLUM_LOGIN")
}

private fun Newborn.named(text: String) =
  if (isSomsak) text else text.replace("Somsak's", "the newborn's").replace("Somsak", "the newborn")

/** The current step with who, app, device, screens, the next action and the test QR it needs. */
fun currentStep(state: StoryState): String = buildString {
  val current = StepEngine.currentStep(state)
  if (current == null) append("All steps are done.")
  else {
    appendLine("Current step: ${current.code} · ${current.title}")
    appendLine("  who:     ${current.who}")
    appendLine("  app:     ${current.app.description}")
    appendLine("  device:  ${current.device}")
    appendLine("  screens: ${current.screens}")
    appendLine("  next:    ${state.newborn.named(current.next)}")
    append(
      "  test QR: ${current.qr?.takeIf { state.newborn.isSomsak }?.let { "$it (trust-demo qr <card>)" } ?: "none"}"
    )
  }
}

private fun incompleteBeforeWritten(state: StoryState): List<Pair<Step, List<Step>>> =
  Step.entries.mapNotNull { step ->
    val later = Step.entries.filter { it > step && state.metCount(it) > 0 }
    if (!state.isDone(step) && later.isNotEmpty()) step to later else null
  }

private fun dateShift(state: StoryState) =
  "Dates: the IG's dates shifted by ${state.shiftDays()} days; " +
    "reset on ${state.resetDay}, today ${state.today}" +
    when {
      state.movedOnOnesa && state.movedOnKesaro -> "\nmoved: story dates −$MOVE_DAYS days"
      state.movedOnOnesa -> "\n! moved on Onesa only; run time move J4.0 to finish Medplum"
      state.movedOnKesaro -> "\n! moved on Medplum only; run time move J4.0 to finish Onesa"
      else -> ""
    }
