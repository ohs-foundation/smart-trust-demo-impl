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
package dev.ohs.trustdemo.onesa.auth

import dev.ohs.trustdemo.onesa.data.sync.Admission
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lock for every token refresh and sign-out in the process, whichever [AuthService] runs it.
 */
private val refreshLock = Mutex()

/**
 * Drives the Authorization-Code-with-PKCE flow against the configured OIDC provider and keeps the
 * [SessionStore] up to date. UI-agnostic; [AuthViewModel] adapts it to Compose state.
 *
 * Unlike opensrp's version, this constructor has NO default parameter values — Koin always supplies
 * [config], [repository], [api] and [admit] (see `authModule` in `AppModule.kt`), so
 * self-constructing defaults would be dead code here. [admit] decides, once the provider has said
 * who signed in, whether that user may use the records on this phone.
 */
internal class AuthService(
  private val config: OAuthConfig,
  private val repository: SessionStore,
  private val api: OidcAuthApi,
  private val admit: suspend (subject: String) -> Admission,
) {
  /** Starts an interactive login. */
  suspend fun login(launcher: AuthorizationLauncherApi): LoginOutcome {
    val pkce = Pkce.generate()
    val state = Pkce.randomState()
    repository.savePending(PendingAuth(codeVerifier = pkce.verifier, state = state))

    val authUrl =
      runCatching { buildAuthorizationUrl(launcher.redirectUri, pkce, state) }
        .getOrElse {
          return LoginOutcome.Error(it.message ?: "Could not reach the sign-in provider")
        }
    return when (val result = launcher.authorize(authUrl)) {
      is AuthResult.Success -> completeLogin(result.callbackUrl, launcher.redirectUri)
      is AuthResult.Canceled -> LoginOutcome.Canceled
      is AuthResult.Failure -> LoginOutcome.Error(result.message)
    }
  }

  /** Exchanges the callback's `code` for tokens and persists the session. */
  private suspend fun completeLogin(callbackUrl: String, redirectUri: String): LoginOutcome {
    val params = Url(callbackUrl).parameters
    params["error"]?.let { error ->
      return LoginOutcome.Error(params["error_description"] ?: error)
    }
    val code = params["code"] ?: return LoginOutcome.Error("Missing authorization code")
    val returnedState = params["state"]

    val pending = repository.takePending()
    if (pending == null || pending.state != returnedState) {
      return LoginOutcome.Error("Invalid state — possible CSRF, please try again")
    }

    return runCatching {
        val tokens = api.exchangeCode(code, pending.codeVerifier, redirectUri)
        val user = api.fetchUserInfo(tokens.accessToken)
        if (user.subject.isBlank()) return LoginOutcome.Error("The sign-in did not say who you are")
        when (val admission = admit(user.subject)) {
          is Admission.Blocked -> return LoginOutcome.Error(admission.message)
          Admission.Allowed -> Unit
        }
        val session = tokens.toSession(user)
        repository.save(session)
        LoginOutcome.Authenticated(session)
      }
      .getOrElse { LoginOutcome.Error(it.message ?: "Sign-in failed") }
  }

  /**
   * Returns a usable session at startup, refreshing an expired access token when possible.
   *
   * Offline-safe: the session is cleared (→ login) ONLY on a definitive provider rejection
   * ([AuthException]). A transient/offline failure keeps the stored session. Server-side revocation
   * while the access token is still locally valid is caught separately (and non-blockingly) by
   * [revalidateSession].
   */
  suspend fun ensureFreshSession(): Session? =
    refreshLock.withLock {
      val session = repository.load() ?: return@withLock null
      if (!session.isAccessTokenExpired(now())) return@withLock session

      tryRefresh(session) ?: repository.session.value
    }

  /**
   * The session for a call straight to a server, as [ensureFreshSession] gives it; concurrent
   * callers share one refresh.
   */
  suspend fun sessionForRequest(): Session? = ensureFreshSession()

  /**
   * Confirms a locally-valid session is still alive server-side via the userinfo probe. Clears it
   * only on a definitive revoke; offline is a no-op. Meant to run in the background after startup
   * so it never blocks an offline launch.
   */
  suspend fun revalidateSession(): Boolean {
    val session = repository.session.value ?: repository.load() ?: return false
    return when (api.sessionStatus(session.accessToken)) {
      SessionStatus.Revoked -> {
        clearIfCurrent(session)
        false
      }
      SessionStatus.Active,
      SessionStatus.Unknown -> true
    }
  }

  /** Refresh hook for [FhirBearerAuthenticator]'s eventual retry path, and internal use. */
  internal suspend fun refreshTokensForRequest(): Session? =
    refreshLock.withLock {
      val session = repository.session.value ?: repository.load() ?: return@withLock null
      tryRefresh(session)
    }

  /**
   * Refreshes [session]'s tokens. What comes back is saved, or the session cleared, only while the
   * stored session is still [session]'s sign-in: a sign-out or another user's login that happened
   * meanwhile is never undone.
   */
  private suspend fun tryRefresh(session: Session): Session? {
    val refreshToken =
      session.refreshToken
        ?: run {
          clearIfCurrent(session)
          return null
        }
    return try {
      val tokens = api.refresh(refreshToken)
      val stored = repository.session.value
      if (stored == null || stored.refreshToken != refreshToken) stored
      else tokens.toSession(stored.user).copy(context = stored.context).also { repository.save(it) }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (rejected: AuthException) {
      clearIfCurrent(session)
      null
    } catch (offline: Throwable) {
      null
    }
  }

  private suspend fun clearIfCurrent(session: Session) {
    if (repository.session.value?.refreshToken == session.refreshToken) repository.clear()
  }

  suspend fun logout() {
    refreshLock.withLock {
      repository.session.value?.refreshToken?.let { api.logout(it) }
      repository.clear()
    }
  }

  private suspend fun buildAuthorizationUrl(
    redirectUri: String,
    pkce: PkcePair,
    state: String,
  ): String =
    URLBuilder(api.endpoints().authorizationEndpoint)
      .apply {
        parameters.append("client_id", config.clientId)
        parameters.append("response_type", "code")
        parameters.append("scope", config.scopes)
        parameters.append("redirect_uri", redirectUri)
        parameters.append("code_challenge", pkce.challenge)
        parameters.append("code_challenge_method", pkce.method)
        parameters.append("state", state)
      }
      .buildString()

  private fun TokenResponse.toSession(user: UserInfo): Session =
    Session(
      accessToken = accessToken,
      refreshToken = refreshToken,
      idToken = idToken,
      expiresInSeconds = expiresIn,
      obtainedAtEpochSeconds = now(),
      user = user,
    )

  @OptIn(ExperimentalTime::class)
  private fun now(): Long = Clock.System.now().toEpochMilliseconds() / 1000
}
