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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.ohs.fhir.datacapture.Questionnaire
import dev.ohs.fhir.datacapture.QuestionnaireConfig
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.UnsignedInt
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.trustdemo.hcert.DvcMin
import dev.ohs.trustdemo.hcert.DvcVaccineDetails
import dev.ohs.trustdemo.onesa.buildAppViewRegistry
import dev.ohs.trustdemo.onesa.data.registration.NewbornRegistration
import dev.ohs.trustdemo.onesa.data.registration.RegistrationRequest
import dev.ohs.trustdemo.onesa.data.registration.ScannedCard
import dev.ohs.trustdemo.onesa.data.repository.GroupRepository
import dev.ohs.trustdemo.onesa.data.repository.InMemorySampleFhirRepository
import dev.ohs.trustdemo.onesa.data.verification.SentReferral
import dev.ohs.trustdemo.onesa.feature.group.list.GroupListContent
import dev.ohs.trustdemo.onesa.feature.group.profile.GroupProfileContent
import dev.ohs.trustdemo.onesa.feature.home.HomeShell
import dev.ohs.trustdemo.onesa.feature.home.ShellHeader
import dev.ohs.trustdemo.onesa.feature.questionnaire.NewbornReview
import dev.ohs.trustdemo.onesa.feature.questionnaire.RegistrationReviewContent
import dev.ohs.trustdemo.onesa.feature.referral.SentReferralsContent
import dev.ohs.trustdemo.onesa.feature.scan.ScanCardContent
import dev.ohs.trustdemo.onesa.feature.scan.ScanCardUiState
import dev.ohs.trustdemo.onesa.ui.ScreenshotHarness.render
import dev.ohs.trustdemo.onesa.ui.theme.OnesaTheme
import dev.ohs.trustdemo.onesa.ui.theme.Tone
import dev.ohs.trustdemo.onesa.util.FhirJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

/** The CHW screens, drawn from the IG's records as the demo's reset leaves them. */
class ChwScreensRenderTest {
  private fun ig(name: String): Resource =
    FhirJson.instance.decodeFromString(
      Resource.serializer(),
      File("../../../ig/resources/$name.json").readText(),
    )

  private val parents = listOf("TrustDemoBounmyPatient", "TrustDemoKhamlaPatient")

  /** The household as the reset seeds it: the two parents, no head named yet. */
  private val seededHousehold =
    (ig("Group-TrustDemoHousehold") as Group).let { group ->
      val members =
        group.member.filter { it.entity.reference?.value?.removePrefix("Patient/") in parents }
      group.copy(
        managingEntity = null,
        member = members,
        quantity = UnsignedInt(value = members.size),
      )
    }

  private val repository =
    InMemorySampleFhirRepository().apply {
      runBlocking {
        upsert(seededHousehold)
        parents.forEach { upsert((ig("Patient-$it") as Patient).copy(link = emptyList())) }
      }
    }

  private val chwHeader =
    ShellHeader(
      title = "Onesa Community Health",
      icon = OnesaIcons.Users,
      subtitle = "Catchment Village 04 • CHW: Vilay",
      status = Status("Offline (28d)", Tone.Warning),
    )

  private val chwTabs = listOf("Households", "Referrals", "Trust list", "Sync")

  private val cards =
    listOf("vp-bcg-freeze-dried" to "XXR-9012", "vp-hepb-peds-monovalent" to "XXR-4821").map {
      (product, lot) ->
      ScannedCard(
        "HC1:$product",
        DvcMin(
          n = "Somsak SENGMANY",
          dob = "2025-10-28",
          s = "male",
          nt = "XXR",
          vx = DvcVaccineDetails(vp = product, dv = "2025-10-28", issuerId = "XXR", bo = lot),
          v = "1.0.0",
        ),
      )
    }

  @Composable
  private fun Registry(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalViewRegistry provides buildAppViewRegistry(), content = content)

  @Composable
  private fun ChwTopBar() =
    OnesaTopBar(
      chwHeader.title,
      OnesaIcons.Back,
      subtitle = chwHeader.subtitle,
      status = chwHeader.status,
      onIconClick = {},
    )

  @Test
  fun householdRegister() {
    val groups = runBlocking { GroupRepository(repository).getGroups() }
    val profiles = runBlocking { GroupRepository(repository).householdProfiles() }
    render("1-06-household-register") {
      Registry {
        HomeShell(
          header = chwHeader,
          tabs = chwTabs,
          selected = 0,
          onSelect = {},
          menu = {},
          actions = listOf(Action("Register Household", {}, primary = false)),
        ) {
          GroupListContent(groups, profiles, place = "Village 04", onGroupClick = {})
        }
      }
    }
  }

  @Test
  fun householdProfile() {
    val profile = runBlocking { GroupRepository(repository).getGroupProfile(seededHousehold.id!!) }
    render("1-07-household-profile") {
      Registry {
        GroupProfileContent(
          state = profile,
          topBar = { ChwTopBar() },
          actions =
            listOf(
              Action("Scan Foreign Vaccination Card", {}),
              Action("Register Newborn", {}, primary = false),
            ),
          onMemberClick = {},
        )
      }
    }
    val registered = InMemorySampleFhirRepository()
    runBlocking {
      listOf(
          "Group-TrustDemoHousehold",
          "Patient-TrustDemoBounmyPatient",
          "Patient-TrustDemoKhamlaPatient",
          "Patient-TrustDemoSomsak",
          "RelatedPerson-TrustDemoBounmy",
          "RelatedPerson-TrustDemoKhamla",
        )
        .forEach { registered.upsert(ig(it)) }
    }
    val after = runBlocking { GroupRepository(registered).getGroupProfile("TrustDemoHousehold") }
    render("1-07-household-profile-registered") {
      Registry {
        GroupProfileContent(
          after,
          topBar = { ChwTopBar() },
          actions = emptyList(),
          onMemberClick = {},
        )
      }
    }
  }

  @Test
  fun scanCardAndDecodedCards() {
    render("1-08-scan-card") {
      ScanCardContent(ScanCardUiState(), true, "", {}, {}, {}, {}, {}, {})
    }
    render("0-01-s2-decoded-cards") {
      ScanCardContent(ScanCardUiState(cards = cards), false, "", {}, {}, {}, {}, {}, {})
    }
  }

  @Test
  fun registrationForm() {
    val questionnaire =
      File(
          "src/commonMain/composeResources/files/configs/Questionnaire-TrustDemoHouseholdRegistration.json"
        )
        .readText()
    val store =
      object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
      }
    render("1-09-registration-form", 360.dp to 1200.dp, settle = 30) {
      CompositionLocalProvider(LocalViewModelStoreOwner provides store) {
        OnesaScaffold(
          topBar = {
            OnesaTopBar(
              "Add a newborn to the household",
              OnesaIcons.Back,
              subtitle = "SDC form • TrustDemoHouseholdRegistration",
              status = Status("Offline (28d)", Tone.Warning),
              onIconClick = {},
            )
          },
          scroll = false,
        ) {
          Questionnaire(
            questionnaireJson = questionnaire,
            config =
              QuestionnaireConfig(
                showReviewPage = false,
                showCancelButton = false,
                submitButtonText = "Continue to Review & Referral",
              ),
            onSubmit = {},
            onCancel = {},
          )
        }
      }
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun registrationReview() {
    val household = seededHousehold
    val members = parents.map { (ig("Patient-$it") as Patient).copy(link = emptyList()) }
    val response =
      FhirJson.instance.decodeFromString(
        QuestionnaireResponse.serializer(),
        """{"resourceType":"QuestionnaireResponse","status":"completed","item":[
        {"linkId":"household","answer":[{"valueString":"HH-XXK-204"}]},
        {"linkId":"given","answer":[{"valueString":"Somsak"}]},
        {"linkId":"family","answer":[{"valueString":"Sengmany"}]},
        {"linkId":"sex","answer":[{"valueCoding":{"code":"male"}}]},
        {"linkId":"birthDate","answer":[{"valueDate":"2025-10-28"}]},
        {"linkId":"birthCountry","answer":[{"valueString":"Taifafeki"}]},
        {"linkId":"foreignCards","answer":[{"valueBoolean":true}]},
        {"linkId":"mother","answer":[{"valueCoding":{"code":"TrustDemoKhamlaPatient"}}]},
        {"linkId":"father","answer":[{"valueCoding":{"code":"TrustDemoBounmyPatient"}}]},
        {"linkId":"relationship","answer":[{"valueCoding":{"code":"FTH"}}]}]}""",
      )
    val request =
      RegistrationRequest(
        response = response,
        household = household,
        mother = members[1],
        father = members[0],
        cards = cards,
        practitionerId = "TrustDemoVilay",
        organizationId = "TrustDemoLomaruDhc",
        now = Instant.parse("2025-11-18T03:20:00Z"),
      )
    fun review(urgent: Boolean) =
      NewbornReview(
        NewbornRegistration.review(NewbornRegistration.transaction(request.copy(urgent = urgent))),
        runBlocking { GroupRepository(repository).getGroupProfile(household.id!!) },
        "Lomaru District Health Center (demonstrator)",
      )
    render("0-02-s3-review", 360.dp to 1240.dp) {
      RegistrationReviewContent(
        review(false),
        topBar = { ChwTopBar() },
        saving = false,
        onSave = {},
      )
    }
    render("0-02-s3-review-urgent", 360.dp to 1240.dp) {
      RegistrationReviewContent(review(true), topBar = { ChwTopBar() }, saving = false, onSave = {})
    }
    runComposeUiTest {
      var urgent by mutableStateOf(false)
      setContent {
        OnesaTheme {
          Registry {
            RegistrationReviewContent(
              review(urgent),
              topBar = {},
              saving = false,
              onSave = {},
              onUrgent = { urgent = it },
            )
          }
        }
      }
      onNodeWithText("Routine Checkup").performClick()
      onNodeWithText("Urgent").performClick()
      onNodeWithText("Urgent").assertExists()
      assertTrue(urgent)
    }
  }

  @Test
  fun chwReferrals() {
    render("chw-referrals") {
      HomeShell(header = chwHeader, tabs = chwTabs, selected = 1, onSelect = {}, menu = {}) {
        SentReferralsContent(
          listOf(
            SentReferral(
              id = "TrustDemoJ2Referral",
              patientName = "Somsak Sengmany",
              status = "active",
              performer = "Lomaru District Health Center (demonstrator)",
              authoredOn = "2025-11-18T10:20:00+07:00",
              reason =
                "Unverified foreign vaccination cards presented on a home visit (primarySource false)",
            )
          ),
          place = "Village 04",
        )
      }
    }
  }
}
