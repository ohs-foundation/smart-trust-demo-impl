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

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.trustdemo.hcert.CertificateFingerprint
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.sharing.ConsentTerms
import dev.ohs.trustdemo.onesa.data.sharing.FamilyRecords
import dev.ohs.trustdemo.onesa.data.sharing.IssuedLink
import dev.ohs.trustdemo.onesa.data.sharing.Look
import dev.ohs.trustdemo.onesa.data.sharing.Person
import dev.ohs.trustdemo.onesa.data.sharing.Refusal
import dev.ohs.trustdemo.onesa.data.sharing.ShareStatus
import dev.ohs.trustdemo.onesa.data.sharing.SharedLink
import dev.ohs.trustdemo.onesa.feature.home.HomeShell
import dev.ohs.trustdemo.onesa.feature.home.ShellHeader
import dev.ohs.trustdemo.onesa.feature.wallet.FullScreenQr
import dev.ohs.trustdemo.onesa.feature.wallet.IssuedLinksContent
import dev.ohs.trustdemo.onesa.feature.wallet.ShareContent
import dev.ohs.trustdemo.onesa.feature.wallet.ShareUiState
import dev.ohs.trustdemo.onesa.feature.wallet.SharedLinksContent
import dev.ohs.trustdemo.onesa.feature.wallet.WalletContent
import dev.ohs.trustdemo.onesa.ui.ScreenshotHarness.render
import dev.ohs.trustdemo.onesa.ui.theme.OnesaColors
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.FhirJson
import java.io.File
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The family wallet's screens, drawn from the IG's J4 records. The QRs hold a TEST link only, and
 * no passcode is ever drawn.
 */
class FamilyScreensRenderTest {
  private fun ig(name: String): Resource =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      File("../../../ig/resources/$name.json").readText(),
    )

  private fun igJson(name: String): JsonObject =
    FhirJson.instance
      .parseToJsonElement(File("../../../ig/resources/$name.json").readText())
      .jsonObject

  private val people =
    listOf(
      Person("TrustDemoSomsak", "Somsak Sengmany"),
      Person("TrustDemoBounmyPatient", "Bounmy Sengmany"),
      Person("TrustDemoKhamlaPatient", "Khamla Sengmany"),
    )

  /** The J3 doses as the clinic's import writes them: with the card's fingerprint. */
  private fun fromCard(name: String) =
    (ig(name) as Immunization).let {
      it.copy(
        identifier =
          listOf(
            Identifier(
              system = Uri(value = CertificateFingerprint.SYSTEM),
              value = FhirString(value = CertificateFingerprint.of("HC1:$name")),
            )
          )
      )
    }

  private val repository =
    InMemorySampleFhirRepository().apply {
      runBlocking {
        listOf(
            "Group-TrustDemoHousehold",
            "Patient-TrustDemoSomsak",
            "Patient-TrustDemoBounmyPatient",
            "Patient-TrustDemoKhamlaPatient",
            "Immunization-TrustDemoOnesaPenta1",
            "Immunization-TrustDemoOnesaBopv1",
            "Immunization-TrustDemoOnesaPcv1",
            "Immunization-TrustDemoOnesaMr1",
          )
          .forEach { upsert(ig(it)) }
        upsert(fromCard("Immunization-TrustDemoJ3HepB"))
        upsert(fromCard("Immunization-TrustDemoJ3Bcg"))
      }
    }

  private val records = FamilyRecords(repository)
  private val family = runBlocking { records.members(people) }
  private val shareDay = LocalDate.parse("2026-10-26")
  private val end = LocalDate.parse("2026-11-09")

  private val header =
    ShellHeader(
      title = "Onesa Family Health Wallet",
      icon = OnesaIcons.Users,
      subtitle = "Sengmany Family • 3 Members • FHIR Group",
      status = Status("National Portal Connected", Tone.Success),
    )

  @Composable
  private fun FamilyTopBar(subtitle: String?, onBack: Boolean = true) =
    OnesaTopBar(
      header.title,
      header.icon,
      subtitle = subtitle,
      status = header.status,
      onIconClick = if (onBack) ({}) else null,
    )

  private fun wallet(name: String, height: Int, content: @Composable () -> Unit) =
    render(name, 360.dp to height.dp, OnesaColors.BrandFamily, content = content)

  /** One TEST link per traveller, ending with the Consent period. */
  private val links =
    people.map {
      IssuedLink(
        consentId = "consent-${it.id}",
        personId = it.id,
        name = it.name!!,
        link = "HC1:TEST-ONLY-RENDER-LINK-${it.id}",
        exp = end.atStartOfDayIn(TimeZone.UTC).epochSeconds + 24 * 60 * 60,
      )
    }

  @Test
  fun householdWallet() {
    val open = setOf("TrustDemoSomsak", "TrustDemoBounmyPatient")
    val doses = runBlocking { open.associateWith { records.doses(it) } }
    wallet("0-05-s6-wallet", 1240) {
      HomeShell(
        header = header,
        tabs = emptyList(),
        selected = 0,
        onSelect = {},
        menu = {},
        actions =
          listOf(
            Action("Request Verifiable Health Links (VHL) for All 3", {}, icon = OnesaIcons.Qr),
            Action("Shared Links", {}, primary = false),
          ),
        caption = "Hosted in Onesa • Records stay in home country custody",
      ) {
        WalletContent(family, open, doses, LocalDate.parse("2026-10-28"), {})
      }
    }
  }

  @Test
  fun shareAndConsent() {
    val terms =
      ConsentTerms.parse(
        File("src/commonMain/composeResources/files/consent/CodeSystem-TrustDemoVhlConsent.json")
          .readText()
      )
    val typed = "1".repeat(4)
    wallet("1-18-share-consent", 1180) {
      ShareContent(
        state =
          ShareUiState(
            members = people,
            family = family,
            selected = people.map { it.id }.toSet(),
            terms = terms,
            today = shareDay,
            end = end,
            passcode = typed,
            again = typed,
          ),
        topBar = { FamilyTopBar("Share records for travel") },
        onToggle = {},
        onPickEnd = {},
        onPasscode = {},
        onAgain = {},
        onAgreeAndShare = {},
      )
    }
  }

  @Test
  fun linkAndFullScreenQr() {
    wallet("0-06-s7-link", 980) {
      IssuedLinksContent(links, family, 0, false, shareDay, {}, {}, {})
    }
    wallet("0-06-s7-full-screen", 720) { FullScreenQr(links, 0, {}, {}) }
  }

  @Test
  fun whoLooked() {
    val clinic =
      igJson("Device-TrustDemoClinicViewer")
        .getValue("deviceName")
        .jsonArray
        .first()
        .jsonObject
        .getValue("name")
        .jsonPrimitive
        .content
    val looks =
      listOf("AuditEvent-TrustDemoJ41Access", "AuditEvent-TrustDemoJ41WrongPasscode").map {
        val audit = igJson(it)
        val released = audit.getValue("outcome").jsonPrimitive.content == "0"
        Look(
          id = audit.getValue("id").jsonPrimitive.content,
          at = Instant.parse(audit.getValue("recorded").jsonPrimitive.content),
          clinic = clinic,
          document = false,
          released = released,
          refusal = if (released) null else Refusal.WrongPasscode,
          host =
            audit
              .getValue("agent")
              .jsonArray
              .first()
              .jsonObject
              .getValue("network")
              .jsonObject
              .getValue("address")
              .jsonPrimitive
              .content,
        )
      }
    val share =
      SharedLink(
        consentId = "TrustDemoJ4Consent",
        personId = "TrustDemoSomsak",
        name = "Somsak Sengmany",
        end = end,
        status = ShareStatus.Active,
        kept = true,
        looks = looks.sortedByDescending { it.at },
      )
    wallet("1-19-who-looked", 720) {
      SharedLinksContent(
        shares = listOf(share),
        index = 0,
        onIndex = {},
        notice = null,
        showAll = false,
        onToggleAll = {},
        topBar = { FamilyTopBar(it) },
        onShowQr = {},
        onWithdraw = {},
      )
    }
  }
}
