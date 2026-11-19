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
package dev.ohs.trustdemo.onesa.data.di

import dev.ohs.fhir.engine.FhirEngineProvider
import dev.ohs.trustdemo.hcert.InMemoryTrustListStorage
import dev.ohs.trustdemo.hcert.TrustListCache
import dev.ohs.trustdemo.onesa.auth.AuthService
import dev.ohs.trustdemo.onesa.auth.AuthViewModel
import dev.ohs.trustdemo.onesa.auth.GeneratedAuthConfig
import dev.ohs.trustdemo.onesa.auth.OAuthConfig
import dev.ohs.trustdemo.onesa.auth.OidcAuthApi
import dev.ohs.trustdemo.onesa.auth.PractitionerDetailsApi
import dev.ohs.trustdemo.onesa.auth.SessionRepository
import dev.ohs.trustdemo.onesa.auth.SessionStore
import dev.ohs.trustdemo.onesa.data.hcert.BundledTrustLists
import dev.ohs.trustdemo.onesa.data.hcert.DataStoreTrustListStorage
import dev.ohs.trustdemo.onesa.data.hcert.HcertService
import dev.ohs.trustdemo.onesa.data.hcert.PinnedTrustList
import dev.ohs.trustdemo.onesa.data.hcert.createTrustListDataStore
import dev.ohs.trustdemo.onesa.data.registration.RegistrationService
import dev.ohs.trustdemo.onesa.data.registration.RegistrationSession
import dev.ohs.trustdemo.onesa.data.registration.ScannedCards
import dev.ohs.trustdemo.onesa.data.repository.FhirEngineRepository
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.repository.GroupRepository
import dev.ohs.trustdemo.onesa.data.repository.PatientContexts
import dev.ohs.trustdemo.onesa.data.repository.PatientRepository
import dev.ohs.trustdemo.onesa.data.schedule.DoseRecording
import dev.ohs.trustdemo.onesa.data.schedule.SchedulePlans
import dev.ohs.trustdemo.onesa.data.schedule.ScheduleService
import dev.ohs.trustdemo.onesa.data.schedule.VisitBooking
import dev.ohs.trustdemo.onesa.data.sharing.LinkStore
import dev.ohs.trustdemo.onesa.data.sharing.SecureLinkStore
import dev.ohs.trustdemo.onesa.data.sharing.ShareService
import dev.ohs.trustdemo.onesa.data.sharing.SharedLinks
import dev.ohs.trustdemo.onesa.data.sync.DataStoreInitialSyncStore
import dev.ohs.trustdemo.onesa.data.sync.InitialSyncStore
import dev.ohs.trustdemo.onesa.data.sync.LocalCopy
import dev.ohs.trustdemo.onesa.data.sync.Outbox
import dev.ohs.trustdemo.onesa.data.sync.StoreOwnership
import dev.ohs.trustdemo.onesa.data.sync.SyncManager
import dev.ohs.trustdemo.onesa.data.sync.createOutboxDataStore
import dev.ohs.trustdemo.onesa.data.sync.createSyncTimestampDataStore
import dev.ohs.trustdemo.onesa.data.verification.DoseMatchService
import dev.ohs.trustdemo.onesa.data.verification.ForeignDoses
import dev.ohs.trustdemo.onesa.data.verification.ReferralInbox
import dev.ohs.trustdemo.onesa.data.verification.VerificationService
import dev.ohs.trustdemo.onesa.feature.group.list.GroupListViewModel
import dev.ohs.trustdemo.onesa.feature.group.profile.GroupProfileViewModel
import dev.ohs.trustdemo.onesa.feature.home.HomeViewModel
import dev.ohs.trustdemo.onesa.feature.patient.list.PatientListViewModel
import dev.ohs.trustdemo.onesa.feature.patient.profile.PatientProfileViewModel
import dev.ohs.trustdemo.onesa.feature.patient.profile.PatientReferralsViewModel
import dev.ohs.trustdemo.onesa.feature.patient.profile.PatientScheduleViewModel
import dev.ohs.trustdemo.onesa.feature.questionnaire.QuestionnaireHostViewModel
import dev.ohs.trustdemo.onesa.feature.questionnaire.QuestionnaireLaunchContext
import dev.ohs.trustdemo.onesa.feature.questionnaire.QuestionnaireService
import dev.ohs.trustdemo.onesa.feature.referral.ClinicScheduleViewModel
import dev.ohs.trustdemo.onesa.feature.referral.ReferralInboxViewModel
import dev.ohs.trustdemo.onesa.feature.referral.ScheduleViewModel
import dev.ohs.trustdemo.onesa.feature.referral.SentReferralsViewModel
import dev.ohs.trustdemo.onesa.feature.referral.VerifyCardsViewModel
import dev.ohs.trustdemo.onesa.feature.role.UserContextViewModel
import dev.ohs.trustdemo.onesa.feature.scan.ScanCardViewModel
import dev.ohs.trustdemo.onesa.feature.sync.InitialSyncViewModel
import dev.ohs.trustdemo.onesa.feature.sync.SyncStatusViewModel
import dev.ohs.trustdemo.onesa.feature.sync.SyncStepsViewModel
import dev.ohs.trustdemo.onesa.feature.trustlist.TrustListViewModel
import dev.ohs.trustdemo.onesa.feature.wallet.IssuedLinksViewModel
import dev.ohs.trustdemo.onesa.feature.wallet.ShareViewModel
import dev.ohs.trustdemo.onesa.feature.wallet.SharedLinksViewModel
import dev.ohs.trustdemo.onesa.feature.wallet.WalletViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private suspend fun registrationSession(auth: AuthService): RegistrationSession? =
  auth.sessionForRequest()?.let { session ->
    val practitioner = session.context?.practitionerId
    val organization = session.context?.organizationId
    if (practitioner == null || organization == null) null
    else RegistrationSession(session.accessToken, practitioner, organization)
  }

/**
 * Binds the production [FhirRepository], backed by [FhirEngineRepository]. Requires a
 * [dev.ohs.fhir.FhirEngine] binding, supplied by each platform's module (see `initKoin` callers).
 */
internal val fhirEngineRepositoryModule = module {
  single<FhirRepository> { FhirEngineRepository(get()) }
}

/**
 * Repositories that only depend on [FhirRepository] — kept separate from
 * [fhirEngineRepositoryModule] so tests can swap in a fake [FhirRepository] without redeclaring
 * these bindings.
 */
internal val repositoryModule = module {
  single { PatientRepository(get()) }
  single { GroupRepository(get()) }
}

internal val serviceModule = module {
  single { ScannedCards() }
  single {
    RegistrationService(
      repository = get(),
      scannedCards = get(),
      session = { registrationSession(get()) },
      outbox = get(),
      refresh = get<LocalCopy>()::refreshAfterWrite,
    )
  }
  single { ReferralInbox(get()) }
  single { PatientContexts(get()) }
  single { ForeignDoses(get()) }
  single {
    VerificationService(
      repository = get(),
      hcert = get(),
      session = { registrationSession(get()) },
      httpClient = OidcAuthApi.defaultHttpClient(),
      baseUrl = GeneratedAuthConfig.FHIR_BASE_URL,
      refresh = get<LocalCopy>()::refreshAfterWrite,
    )
  }
  single {
    DoseMatchService(
      repository = get(),
      session = { registrationSession(get()) },
      httpClient = OidcAuthApi.defaultHttpClient(),
      baseUrl = GeneratedAuthConfig.FHIR_BASE_URL,
      refresh = get<LocalCopy>()::refreshAfterWrite,
    )
  }
  single { LocalCopy(get()) }
  single { ScheduleService(get(), SchedulePlans::load) }
  single {
    VisitBooking(
      schedule = get(),
      session = { registrationSession(get()) },
      httpClient = OidcAuthApi.defaultHttpClient(),
      baseUrl = GeneratedAuthConfig.FHIR_BASE_URL,
      refresh = get<LocalCopy>()::refreshAfterWrite,
      caughtUp = get<LocalCopy>()::caughtUp,
    )
  }
  single {
    DoseRecording(
      repository = get(),
      choices = get<DoseMatchService>()::choices,
      session = { registrationSession(get()) },
      httpClient = OidcAuthApi.defaultHttpClient(),
      baseUrl = GeneratedAuthConfig.FHIR_BASE_URL,
      refresh = get<LocalCopy>()::refreshAfterWrite,
      caughtUp = get<LocalCopy>()::caughtUp,
    )
  }
  factory { QuestionnaireService(get(), get(), get()) { registrationSession(get()) } }
  single<LinkStore> { SecureLinkStore }
  single {
    ShareService(
      repository = get(),
      accessToken = { get<AuthService>().sessionForRequest()?.accessToken },
      httpClient = OidcAuthApi.defaultHttpClient(),
      baseUrl = GeneratedAuthConfig.VHL_BASE_URL,
      links = get(),
      fhirBaseUrl = GeneratedAuthConfig.FHIR_BASE_URL,
    )
  }
  single {
    SharedLinks(
      repository = get(),
      accessToken = { get<AuthService>().sessionForRequest()?.accessToken },
      httpClient = OidcAuthApi.defaultHttpClient(),
      baseUrl = GeneratedAuthConfig.VHL_BASE_URL,
      links = get(),
      family = { SessionRepository.session.value?.context?.patientId },
      names = get<ShareService>()::nameOf,
      refresh = get<LocalCopy>()::refreshAfterWrite,
    )
  }
}

/**
 * [SyncManager] isn't bound here — each platform's `initKoin` caller supplies its own
 * implementation, constructing `AppFhirSyncTask` directly rather than through Koin (see
 * `WorkManagerSyncManager` on Android and `ForegroundSyncManager` on JVM).
 */
internal val syncModule = module {
  single { FhirEngineProvider.getFhirDataStore() }
  single<InitialSyncStore> { DataStoreInitialSyncStore(createSyncTimestampDataStore()) }
  single { StoreOwnership(get(), createSyncTimestampDataStore(), get(), get()) }
  single {
    Outbox(
      dataStore = createOutboxDataStore(),
      httpClient = OidcAuthApi.defaultHttpClient(),
      baseUrl = GeneratedAuthConfig.FHIR_BASE_URL,
      accessToken = { get<AuthService>().sessionForRequest()?.accessToken },
    )
  }
}

/**
 * `SessionStore`/`SessionRepository`/`AuthService` — everything downstream of the plain
 * `SessionRepository` object (kept outside Koin; see its kdoc) is Koin-injected here.
 */
internal val authModule = module {
  single { OAuthConfig.Default }
  single<SessionStore> { SessionRepository }
  single { OidcAuthApi(get()) }
  single { AuthService(get(), get(), get(), get<StoreOwnership>()::admit) }
  single { PractitionerDetailsApi() }
}

/**
 * The local trust list is fetched and kept on the device; the GDHCN DEV snapshot ships with the
 * app. Both are verified against signer documents bundled in the app.
 */
internal val hcertModule = module {
  single {
    HcertService(
      local =
        PinnedTrustList(
          name = "Local test list",
          signer = BundledTrustLists::localSigner,
          cache =
            TrustListCache(
              DataStoreTrustListStorage(createTrustListDataStore()),
              url = GeneratedAuthConfig.TRUST_LIST_URL,
              httpClient = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 15_000 } },
            ),
        ),
      gdhcnDev = BundledTrustLists.gdhcnDev(InMemoryTrustListStorage()),
    )
  }
}

internal val viewModelModule = module {
  viewModel { PatientListViewModel(get()) }
  viewModel { (patientId: String) -> PatientProfileViewModel(patientId, get(), get()) }
  viewModel { GroupListViewModel(get()) }
  viewModel { (groupId: String) -> GroupProfileViewModel(groupId, get()) }
  viewModel { (questionnaireId: String, launchContext: QuestionnaireLaunchContext) ->
    QuestionnaireHostViewModel(questionnaireId, launchContext, get())
  }
  viewModel { HomeViewModel(get<LocalCopy>(), get()) }
  viewModel { AuthViewModel(get(), get()) }
  viewModel {
    UserContextViewModel(
      get<PractitionerDetailsApi>()::fetch,
      get(),
      resetSync = get<InitialSyncStore>()::reset,
      freshSession = get<AuthService>()::sessionForRequest,
    )
  }
  viewModel { InitialSyncViewModel(get(), get()) }
  viewModel { SyncStepsViewModel(get()) { SessionRepository.session.value?.context } }
  viewModel { SyncStatusViewModel(get(), get()) { SessionRepository.session.value?.context } }
  viewModel { TrustListViewModel(get()) }
  viewModel { ScanCardViewModel(get(), get()) }
  viewModel { ReferralInboxViewModel(get()) }
  viewModel {
    SentReferralsViewModel(get()) { SessionRepository.session.value?.context?.practitionerId }
  }
  viewModel { WalletViewModel(get()) { SessionRepository.session.value?.context?.patientId } }
  viewModel { ShareViewModel(get(), get()) }
  viewModel { SharedLinksViewModel(get()) }
  viewModel { (consentIds: List<String>) ->
    IssuedLinksViewModel(consentIds, get<ShareService>(), get())
  }
  viewModel { (referralId: String) -> VerifyCardsViewModel(referralId, get(), get(), get(), get()) }
  viewModel { (referralId: String) ->
    ScheduleViewModel(referralId, get(), get(), get(), get(), get(), get())
  }
  viewModel { (patientId: String) -> PatientScheduleViewModel(patientId, get()) }
  viewModel { (patientId: String) -> PatientReferralsViewModel(patientId, get()) }
  viewModel { ClinicScheduleViewModel(get()) }
}
