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
package dev.ohs.trustdemo.onesa.data.schedule

import dev.ohs.fhir.model.r4.PlanDefinition
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.WorkflowRepository
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.util.FhirJson
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The two PlanDefinitions the schedule runs: TD.DT.03, applied to one dose at a time, and the
 * national infant schedule with TD.DT.04. They are bundled with the app and never stored.
 */
class SchedulePlans(val doseCounts: PlanDefinition, val infantSchedule: PlanDefinition) {
  companion object {
    private const val DIRECTORY = "files/protocols"

    fun parse(doseCounts: String, infantSchedule: String): SchedulePlans =
      SchedulePlans(plan(doseCounts), plan(infantSchedule))

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): SchedulePlans {
      suspend fun read(name: String) = Res.readBytes("$DIRECTORY/$name").decodeToString()
      return parse(
        read("PlanDefinition-TrustDemoDT03.json"),
        read("PlanDefinition-TrustDemoOnesaInfantSchedule.json"),
      )
    }

    private fun plan(json: String): PlanDefinition =
      FhirJson.instance.decodeFromString(Resource.serializer(), json) as PlanDefinition
  }
}

/** The library wants a store; the schedule only evaluates, so nothing is read or written. */
internal object NoWorkflowStore : WorkflowRepository {
  override suspend fun read(type: String, id: String): Resource? = null

  override suspend fun create(resource: Resource): String = unsupported()

  override suspend fun update(resource: Resource) = unsupported()

  override suspend fun delete(type: String, id: String) = unsupported()

  override suspend fun searchByReferenceParam(
    type: String,
    param: String,
    referenceValue: String,
  ): List<Resource> = emptyList()

  override suspend fun searchByUri(type: String, param: String, uri: String): List<Resource> =
    emptyList()

  private fun unsupported(): Nothing = error("The schedule does not store through the library.")
}
