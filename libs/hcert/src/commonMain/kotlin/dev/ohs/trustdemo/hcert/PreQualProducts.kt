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
package dev.ohs.trustdemo.hcert

/** The WHO SMART Product Dataset for Prequalified Vaccines (smart-pcmt-vaxprequal). */
object PreQualTerminology {
  const val PRODUCT_IDS = "http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualProductIds"
  const val VACCINE_TYPE = "http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualVaccineType"
}

/** What the app knows about one PreQual product: enough to show a name and derive a type. */
data class PreQualProduct(
  val id: String,
  val display: String,
  /** The FVP-P product code, as the published CodeSystem shows it. */
  val productCode: String? = null,
  /** PreQualVaccineType code, when known. */
  val vaccineType: String? = null,
)

/**
 * Display names for PreQual product identifiers. The published CodeSystem's displays are the
 * product codes ("FVP-P-132 (a3K3X000006MiiXUAS)"), not names, so the demo products carry a local
 * display table that wins over the CodeSystem; everything else falls back to the CodeSystem, then
 * to the bare identifier. Codes are never translated here: this is display only.
 */
class PreQualProducts(private val products: Map<String, PreQualProduct>) {
  fun find(vp: String): PreQualProduct? = products[vp]

  fun display(vp: String): String = products[vp]?.display ?: vp

  fun vaccineType(vp: String): String? = products[vp]?.vaccineType

  fun plus(other: PreQualProducts): PreQualProducts = PreQualProducts(products + other.products)

  companion object {
    /**
     * The journeys' products, named as the IG's TrustDemoVaccineProducts value set names them, and
     * the WHO worked example's BCG.
     */
    val DEMO: PreQualProducts =
      PreQualProducts(
        listOf(
            PreQualProduct(
              id = "a3K3X000006MiiXUAS",
              display =
                "Hepatitis B Vaccine (rDNA) (Paediatric) (HepB), Serum Institute of India, " +
                  "Vial, 10 doses [FVP-P-132]",
              productCode = "FVP-P-132",
              vaccineType = "HepB",
            ),
            PreQualProduct(
              id = "a3K3X000006MiisUAC",
              display = "BCG Vaccine (BCG), Serum Institute of India, Vial, 20 doses [FVP-P-117]",
              productCode = "FVP-P-117",
              vaccineType = "BCG",
            ),
            PreQualProduct(
              id = "a3K3X000006MijGUAS",
              display = "Eupenta (DTwP-HepB-Hib), LG Chem Ltd (LGC), Vial, 10 doses [FVP-P-305]",
              productCode = "FVP-P-305",
              vaccineType = "DTwP-HepB-Hib",
            ),
            PreQualProduct(
              id = "a3K3X000006MijBUAS",
              display =
                "Poliomyelitis Vaccine (Oral) Bivalent types 1 and 3 (bOPV), Serum Institute of " +
                  "India, Vial, 10 doses [FVP-P-281]",
              productCode = "FVP-P-281",
              vaccineType = "bOPV",
            ),
            PreQualProduct(
              id = "a3K3X000006MijJUAS",
              display = "PNEUMOSIL® (PCV), Serum Institute of India, Vial, 5 doses [FVP-P-385]",
              productCode = "FVP-P-385",
              vaccineType = "PCV",
            ),
            PreQualProduct(
              id = "a3K3X000006MiimUAC",
              display =
                "Measles and Rubella Vaccine Live Attenuated (MR), Serum Institute of India, " +
                  "Vial, 10 doses [FVP-P-140]",
              productCode = "FVP-P-140",
              vaccineType = "MR",
            ),
            PreQualProduct(
              id = "a3K3X000006MiiCUAS",
              display = "BCG Vaccine",
              productCode = "FVP-P-73",
              vaccineType = "BCG",
            ),
          )
          .associateBy { it.id }
      )
  }
}
