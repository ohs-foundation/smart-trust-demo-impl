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
@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKotlinMultiplatformLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeHotReload)
  alias(libs.plugins.kotlinSerialization)
  id("dev.ohs.ig-codegen")
  id("spotless-conventions")
}

// --- OAuth / OIDC config ------------------------------------------------------
// Provider-agnostic: the app resolves endpoints via OIDC discovery
// ({issuer}/.well-known/openid-configuration).
fun authProp(key: String, default: String): String =
  nonBlankEnv(key).orNull ?: localProperties[key]?.takeIf { it.isNotBlank() } ?: default

// Auth/deployment settings live in local.properties (git-ignored), with env-var overrides for CI.
val localProperties: Map<String, String> =
  providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text ->
      val props = Properties().apply { load(text.reader()) }
      props.stringPropertyNames().associateWith(props::getProperty)
    }
    .getOrElse(emptyMap())

/*
 * Reads an environment variable, but yields a value only when it is non-blank.
 * Blank or whitespace-only vars are treated as absent, leaving the provider
 * empty so downstream `.getOrElse(...)` / `.orNull` fallbacks take over. This
 * stops an accidentally exported-but-empty var (e.g. `VERSION_NAME=`) from
 * slipping past those fallbacks.
 */
fun nonBlankEnv(name: String): Provider<String> =
  providers.environmentVariable(name).filter { it.isNotBlank() }

/*
 * Resolved once at configuration time. `generateAuthConfig` bakes these into
 * GeneratedAuthConfig, and the redirect scheme/host additionally drive the Android
 * manifest placeholders below, so the LoginRedirectActivity intent-filter cannot
 * drift from the config the app actually authenticates with.
 */
val resolvedIssuer = authProp("OAUTH_ISSUER", "https://keycloak.example.org/realms/ohs-player")
val resolvedClientId = authProp("OAUTH_CLIENT_ID", "ohs-player-reference-app")
val resolvedRedirectScheme = authProp("OAUTH_REDIRECT_SCHEME", "dev.ohs.trustdemo.onesa")
val resolvedRedirectHost = authProp("OAUTH_REDIRECT_HOST", "auth")
val resolvedDesktopPort = authProp("OAUTH_DESKTOP_REDIRECT_PORT", "8765")
val resolvedScopes = authProp("OAUTH_SCOPES", "openid profile email offline_access")
val resolvedTrustListUrl =
  authProp("TRUST_LIST_URL", "http://trust.gdhcn.example:8480/local-test-list.json")
// The FHIR Engine resolves sync paths against this URL, so it must end in a slash.
// See kotlin-fhir-engine#90 and issue #84; a base without the slash loses its
// last segment when the engine resolves paths against it.
val resolvedFhirBaseUrl =
  authProp("FHIR_BASE_URL", "https://gateway.onesa.example/fhir/").let {
    if (it.endsWith("/")) it else "$it/"
  }

val resolvedVhlBaseUrl = authProp("VHL_BASE_URL", "https://vhl.onesa.example.org:8443").trimEnd('/')

kotlin {
  jvmToolchain(21)

  // Desktop lacks a native OS background scheduler, so it uses the "foregroundSync" source set
  // (see `foregroundSyncMain/.../data/sync/Sync.kt`) with a coroutine-based scheduler.
  applyDefaultHierarchyTemplate { common { group("foregroundSync") { withJvm() } } }

  // fhir-engine's Android artifact ships inline functions (e.g. Sync.oneTimeSync) compiled at JVM
  // target 21 — inlining them requires this target to be at least as high.
  androidLibrary {
    namespace = "dev.ohs.trustdemo.onesa.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    androidResources { enable = true }
  }
  jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

  // expect/actual classes (AuthorizationLauncher) are stable enough for our use.
  compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.libs.hcert)
      implementation(libs.ohs.player.client)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material)
      implementation(libs.compose.material3)
      implementation(libs.compose.adaptive)
      implementation(libs.compose.adaptive.layout)
      implementation(libs.compose.adaptive.navigation)
      implementation(libs.compose.material3.adaptive.navigation.suite)
      implementation(libs.compose.materialIconsCore)
      implementation(libs.compose.ui)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.androidx.lifecycle.viewmodelCompose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
      implementation(libs.kermit)
      implementation(libs.qrose)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.datetime)
      implementation(libs.navigation.compose)
      implementation(project.dependencies.platform(libs.koin.bom))
      implementation(libs.koin.core)
      implementation(libs.koin.compose)
      implementation(libs.koin.composeViewmodel)
      implementation(libs.ohs.fhir.engine)
      implementation(libs.ohs.fhir.model.r4)
      implementation(libs.ohs.fhir.workflow)
      implementation(libs.fhir.data.capture)
      // Auth: shared OAuth2/PKCE client, secure session storage, SHA-256 for PKCE.
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.auth)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)
      implementation(libs.ksafe)
      implementation(project.dependencies.platform(libs.kotlincrypto.hash.bom))
      implementation(libs.kotlincrypto.hash.sha2)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.compose.uiTest)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
    androidMain.dependencies {
      implementation(libs.androidx.browser)
      implementation(libs.androidx.work.runtime)
      implementation(libs.kscan)
    }
    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutinesSwing)
      implementation(libs.ktor.client.cio)
    }
    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.koin.test)
      implementation(libs.hcert.core)
      implementation(projects.libs.trustkit)
      implementation(projects.tools.control)
      implementation(projects.services.vhl)
    }
  }
}

compose.resources { packageOfResClass = "dev.ohs.trustdemo.onesa.generated.resources" }

val authConfigOutputDir = layout.buildDirectory.dir("generated/authconfig/commonMain/kotlin")

val generateAuthConfig =
  tasks.register("generateAuthConfig") {
    // Copied into locals so the doLast lambda below closes over plain Strings.
    // Reading a script-level `val` from inside doLast captures a Gradle script
    // object reference, which the configuration cache cannot serialize.
    val issuer = resolvedIssuer
    val clientId = resolvedClientId
    val redirectScheme = resolvedRedirectScheme
    val redirectHost = resolvedRedirectHost
    val desktopPort = resolvedDesktopPort
    val scopes = resolvedScopes
    val fhirBaseUrl = resolvedFhirBaseUrl
    val trustListUrl = resolvedTrustListUrl
    val vhlBaseUrl = resolvedVhlBaseUrl
    val versionName = releaseVersionName
    val versionCode = releaseVersionCode
    val outDir = authConfigOutputDir
    inputs.property("issuer", issuer)
    inputs.property("clientId", clientId)
    inputs.property("redirectScheme", redirectScheme)
    inputs.property("redirectHost", redirectHost)
    inputs.property("desktopPort", desktopPort)
    inputs.property("scopes", scopes)
    inputs.property("fhirBaseUrl", fhirBaseUrl)
    inputs.property("trustListUrl", trustListUrl)
    inputs.property("vhlBaseUrl", vhlBaseUrl)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.dir(outDir)
    doLast {
      val pkgDir = outDir.get().asFile.resolve("dev/ohs/trustdemo/onesa/auth").apply { mkdirs() }
      pkgDir
        .resolve("GeneratedAuthConfig.kt")
        .writeText(
          """
        |// Generated by the :shared generateAuthConfig task. Do not edit.
        |// Values come from local.properties / env vars; see local.properties.sample.
        |package dev.ohs.trustdemo.onesa.auth
        |
        |internal object GeneratedAuthConfig {
        |  const val ISSUER: String = "$issuer"
        |  const val CLIENT_ID: String = "$clientId"
        |  const val REDIRECT_SCHEME: String = "$redirectScheme"
        |  const val REDIRECT_HOST: String = "$redirectHost"
        |  const val DESKTOP_REDIRECT_PORT: Int = $desktopPort
        |  const val SCOPES: String = "$scopes"
        |  const val FHIR_BASE_URL: String = "$fhirBaseUrl"
        |  const val TRUST_LIST_URL: String = "$trustListUrl"
        |  const val VHL_BASE_URL: String = "$vhlBaseUrl"
        |}
        |
        """
            .trimMargin()
        )
      pkgDir
        .resolve("GeneratedAppInfo.kt")
        .writeText(
          """
        |// Generated by the :shared generateAuthConfig task. Do not edit.
        |package dev.ohs.trustdemo.onesa.auth
        |
        |internal object GeneratedAppInfo {
        |  const val VERSION_NAME: String = "$versionName"
        |  const val VERSION_CODE: Int = $versionCode
        |}
        |
        """
            .trimMargin()
        )
    }
  }

kotlin.sourceSets.named("commonMain") { kotlin.srcDir(authConfigOutputDir) }

kotlin.sourceSets.named("jvmTest") {
  resources.srcDir(rootProject.file("testkit"))
  resources.srcDir(rootProject.file("testkit/who"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  dependsOn(generateAuthConfig)
}

// Release version info (used by generateAuthConfig)
val keystoreProperties: Map<String, String> =
  providers
    .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
    .asText
    .map { text ->
      val props = Properties().apply { load(text.reader()) }
      props.stringPropertyNames().associateWith(props::getProperty)
    }
    .getOrElse(emptyMap())

fun envOrKeystore(envName: String, fileKey: String): String? =
  nonBlankEnv(envName).orNull ?: keystoreProperties[fileKey]?.takeIf { it.isNotBlank() }

val releaseVersionName: String =
  nonBlankEnv("VERSION_NAME").map { it.removePrefix("v") }.getOrElse("0.0.0-dev")

val releaseVersionCode: Int =
  nonBlankEnv("VERSION_CODE")
    .map { raw -> raw.toIntOrNull() ?: error("VERSION_CODE='$raw' must be an integer") }
    .getOrElse(1)

igCodegen {
  // sourcesDir defaults to src/commonMain/composeResources/files
  packageName = "dev.ohs.trustdemo.onesa.generated"
}
