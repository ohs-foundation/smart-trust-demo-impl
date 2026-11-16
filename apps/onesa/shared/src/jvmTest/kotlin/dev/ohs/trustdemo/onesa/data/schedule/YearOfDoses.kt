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

import dev.ohs.trustdemo.onesa.data.verification.ScanTransactions

/**
 * Somsak's doses as `time move J4.0 --with-year-doses` leaves them, born 2025-11-18: the six
 * schedule doses, the 6-week ones given a day after the visit J3b booked.
 */
internal fun yearOfDoses(): List<Triple<String, String, String>> =
  listOf(
    Triple("hepb", "XXK-EPI-HEPB-BD", "2025-11-18"),
    Triple("bcg", "XXK-EPI-BCG", "2025-12-16"),
    Triple("penta", "XXK-EPI-PENTA-1", "2025-12-31"),
    Triple("opv", "XXK-EPI-OPV-1", "2025-12-31"),
    Triple("pcv", "XXK-EPI-PCV-1", "2025-12-31"),
    Triple("mr", "XXK-EPI-MR-1", "2026-08-18"),
  )

/** The recommendation J3b booked for the 6-week doses, still `due`. */
internal val bookedVisitRecommendation =
  """{"resourceType":"ImmunizationRecommendation","id":"visit","date":"2025-12-16",
  "patient":{"reference":"Patient/somsak"},"recommendation":[""" +
    listOf("PENTA-1" to "Pentavalent 1", "OPV-1" to "bOPV 1", "PCV-1" to "PCV 1").joinToString(
      ","
    ) { (code, display) ->
      """{"vaccineCode":[{"coding":[{"system":"${ScanTransactions.EPI_SYSTEM}","code":"XXK-EPI-$code","display":"$display"}]}],
      "forecastStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/immunization-recommendation-status","code":"due"}]},
      "dateCriterion":[{"code":{"coding":[{"system":"http://loinc.org","code":"30980-7"}]},"value":"2025-12-30"}]}"""
    } +
    "]}"

/** Its reminder Task, still `requested`. */
internal const val bookedVisitReminder =
  """{"resourceType":"Task","id":"reminder","status":"requested","intent":"plan",
  "focus":{"reference":"ImmunizationRecommendation/visit"},"for":{"reference":"Patient/somsak"},
  "restriction":{"period":{"start":"2025-12-30","end":"2026-01-06"}}}"""
