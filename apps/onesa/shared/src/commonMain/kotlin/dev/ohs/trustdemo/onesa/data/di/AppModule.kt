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
import dev.ohs.trustdemo.onesa.auth.AuthService
import dev.ohs.trustdemo.onesa.auth.AuthViewModel
import dev.ohs.trustdemo.onesa.auth.OAuthConfig
import dev.ohs.trustdemo.onesa.auth.OidcAuthApi
import dev.ohs.trustdemo.onesa.auth.PractitionerDetailsApi
import dev.ohs.trustdemo.onesa.auth.SessionRepository
import dev.ohs.trustdemo.onesa.auth.SessionStore
import dev.ohs.trustdemo.onesa.data.repository.FhirEngineRepository
import dev.ohs.trustdemo.onesa.data.repository.FhirRepository
import dev.ohs.trustdemo.onesa.data.repository.GroupRepository
import dev.ohs.trustdemo.onesa.data.repository.PatientRepository
import dev.ohs.trustdemo.onesa.data.sync.DataStoreInitialSyncStore
import dev.ohs.trustdemo.onesa.data.sync.InitialSyncStore
import dev.ohs.trustdemo.onesa.data.sync.createSyncTimestampDataStore
import dev.ohs.trustdemo.onesa.feature.group.list.GroupListViewModel
import dev.ohs.trustdemo.onesa.feature.group.profile.GroupProfileViewModel
import dev.ohs.trustdemo.onesa.feature.home.HomeViewModel
import dev.ohs.trustdemo.onesa.feature.patient.list.PatientListViewModel
import dev.ohs.trustdemo.onesa.feature.patient.profile.PatientProfileViewModel
import dev.ohs.trustdemo.onesa.feature.questionnaire.QuestionnaireHostViewModel
import dev.ohs.trustdemo.onesa.feature.questionnaire.QuestionnaireLaunchContext
import dev.ohs.trustdemo.onesa.feature.questionnaire.QuestionnaireService
import dev.ohs.trustdemo.onesa.feature.role.UserContextViewModel
import dev.ohs.trustdemo.onesa.feature.sync.InitialSyncViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

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

internal val serviceModule = module { factory { QuestionnaireService(get()) } }

/**
 * [SyncManager] isn't bound here — each platform's `initKoin` caller supplies its own
 * implementation, constructing `AppFhirSyncTask` directly rather than through Koin (see
 * `WorkManagerSyncManager` on Android and `ForegroundSyncManager` on JVM).
 */
internal val syncModule = module {
  single { FhirEngineProvider.getFhirDataStore() }
  single<InitialSyncStore> { DataStoreInitialSyncStore(createSyncTimestampDataStore()) }
}

/**
 * `SessionStore`/`SessionRepository`/`AuthService` — everything downstream of the plain
 * `SessionRepository` object (kept outside Koin; see its kdoc) is Koin-injected here.
 */
internal val authModule = module {
  single { OAuthConfig.Default }
  single<SessionStore> { SessionRepository }
  single { OidcAuthApi(get()) }
  single { AuthService(get(), get(), get()) }
  single { PractitionerDetailsApi() }
}

internal val viewModelModule = module {
  viewModel { PatientListViewModel(get()) }
  viewModel { (patientId: String) -> PatientProfileViewModel(patientId, get()) }
  viewModel { GroupListViewModel(get()) }
  viewModel { (groupId: String) -> GroupProfileViewModel(groupId, get()) }
  viewModel { (questionnaireId: String, launchContext: QuestionnaireLaunchContext) ->
    QuestionnaireHostViewModel(questionnaireId, launchContext, get())
  }
  viewModel { HomeViewModel(get(), get()) }
  viewModel { AuthViewModel(get(), get()) }
  viewModel {
    UserContextViewModel(
      get<PractitionerDetailsApi>()::fetch,
      get(),
      resetSync = get<InitialSyncStore>()::reset,
    )
  }
  viewModel { InitialSyncViewModel(get(), get()) }
}
