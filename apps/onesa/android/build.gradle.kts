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
import java.net.URI
import java.util.Properties

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.composeCompiler)
  id("spotless-conventions")
}

android {
  namespace = "dev.ohs.trustdemo.onesa"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  // Release signing inputs: env vars first (CI), then keystore.properties as a
  // dev-time fallback. Read via the providers API so the config cache tracks them.
  val keystoreProperties: Map<String, String> =
    providers
      .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
      .asText
      .map { text: String ->
        val props = Properties().apply { load(text.reader()) }
        props.stringPropertyNames().associateWith(props::getProperty)
      }
      .getOrElse(emptyMap())

  /*
   * Reads an environment variable, but yields a value only when it is non-blank.
   * Blank or whitespace-only vars are treated as absent, leaving the provider
   * empty so downstream `.getOrElse(...)` / `.orNull` fallbacks take over.
   */
  fun nonBlankEnv(name: String): Provider<String> =
    providers.environmentVariable(name).filter { it.isNotBlank() }

  fun envOrKeystore(envName: String, fileKey: String): String? =
    nonBlankEnv(envName).orNull ?: keystoreProperties[fileKey]?.takeIf { it.isNotBlank() }

  val releaseVersionName: String =
    nonBlankEnv("VERSION_NAME").map { it.removePrefix("v") }.getOrElse("0.0.0-dev")

  val releaseVersionCode: Int =
    nonBlankEnv("VERSION_CODE")
      .map { raw -> raw.toIntOrNull() ?: error("VERSION_CODE='$raw' must be an integer") }
      .getOrElse(1)

  // --- OAuth / OIDC config --------------------------------------------------
  val localProperties: Map<String, String> =
    providers
      .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
      .asText
      .map { text: String ->
        val props = Properties().apply { load(text.reader()) }
        props.stringPropertyNames().associateWith(props::getProperty)
      }
      .getOrElse(emptyMap())

  fun authProp(key: String, default: String): String =
    nonBlankEnv(key).orNull ?: localProperties[key]?.takeIf { it.isNotBlank() } ?: default

  val resolvedRedirectScheme = authProp("OAUTH_REDIRECT_SCHEME", "dev.ohs.trustdemo.onesa")
  val resolvedRedirectHost = authProp("OAUTH_REDIRECT_HOST", "auth")

  defaultConfig {
    applicationId = "dev.ohs.trustdemo.onesa"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = releaseVersionCode
    versionName = releaseVersionName
    manifestPlaceholders["oauthRedirectScheme"] = resolvedRedirectScheme
    manifestPlaceholders["oauthRedirectHost"] = resolvedRedirectHost
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

  val keystorePath = envOrKeystore("ANDROID_KEYSTORE_PATH", "KEYSTORE_PATH")
  val keystoreAlias = envOrKeystore("ANDROID_KEY_ALIAS", "KEY_ALIAS")
  val keystoreKeyPassword = envOrKeystore("ANDROID_KEY_PASSWORD", "KEY_PASSWORD")
  val keystoreStorePassword = envOrKeystore("ANDROID_STORE_PASSWORD", "STORE_PASSWORD")

  val hasReleaseSigning: Boolean =
    !keystorePath.isNullOrBlank() &&
      !keystoreAlias.isNullOrBlank() &&
      !keystoreKeyPassword.isNullOrBlank() &&
      !keystoreStorePassword.isNullOrBlank()

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        enableV1Signing = false
        enableV2Signing = true
        storeFile = file(keystorePath!!)
        keyAlias = keystoreAlias
        keyPassword = keystoreKeyPassword
        storePassword = keystoreStorePassword
      }
    }
  }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

/**
 * The debug variant's network security config: cleartext to the demo's sslip.io names as in main,
 * and the demo stack's CA, from `stack/tls/ca.crt`, trusted for the exact host of `VHL_BASE_URL`
 * alone. Without the CA no trust anchor is generated: the build succeeds, warns, and the VHL
 * service stays unreachable until the app is built again after `make stack-up`.
 */
abstract class GenerateDebugNetworkConfig : DefaultTask() {
  @get:Input @get:Optional abstract val ca: Property<String>

  @get:Input abstract val vhlHost: Property<String>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun write() {
    val res = outputDir.get().asFile
    res.resolve("raw").deleteRecursively()
    val certificate = ca.orNull
    val trusted =
      if (certificate == null) {
        logger.warn("stack/tls/ca.crt is missing: the debug build does not trust the demo CA.")
        ""
      } else {
        res.resolve("raw").apply { mkdirs() }.resolve("demo_ca.pem").writeText(certificate)
        """
        |  <domain-config>
        |    <domain includeSubdomains="false">${vhlHost.get()}</domain>
        |    <trust-anchors>
        |      <certificates src="system" />
        |      <certificates src="@raw/demo_ca" />
        |    </trust-anchors>
        |  </domain-config>
        """
          .trimMargin()
      }
    res
      .resolve("xml")
      .apply { mkdirs() }
      .resolve("network_security_config.xml")
      .writeText(
        """
      |<?xml version="1.0" encoding="utf-8"?>
      |<network-security-config>
      |  <domain-config cleartextTrafficPermitted="true">
      |    <domain includeSubdomains="true">sslip.io</domain>
      |  </domain-config>
      |$trusted
      |</network-security-config>
      |
      """
          .trimMargin()
      )
  }
}

val demoCa =
  providers.fileContents(rootProject.layout.projectDirectory.file("stack/tls/ca.crt")).asText

val vhlHostProvider: Provider<String> =
  providers
    .environmentVariable("VHL_BASE_URL")
    .filter { it.isNotBlank() }
    .orElse(
      providers
        .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText
        .map { text ->
          Properties().apply { load(text.reader()) }.getProperty("VHL_BASE_URL").orEmpty()
        }
        .filter { it.isNotBlank() }
    )
    .orElse("https://vhl.onesa.example.org:8443")
    .map { URI(it).host }

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
  val generate =
    tasks.register<GenerateDebugNetworkConfig>(
      "generate${variant.name.replaceFirstChar(Char::uppercase)}NetworkConfig"
    ) {
      ca.set(demoCa)
      vhlHost.set(vhlHostProvider)
    }
  variant.sources.res?.addGeneratedSourceDirectory(generate, GenerateDebugNetworkConfig::outputDir)
}

dependencies {
  implementation(projects.apps.onesa.shared)
  implementation(libs.compose.uiToolingPreview)
  implementation(libs.androidx.activity.compose)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.androidx.browser)
  implementation(libs.androidx.work.runtime)
  debugImplementation(libs.compose.uiTooling)
}
