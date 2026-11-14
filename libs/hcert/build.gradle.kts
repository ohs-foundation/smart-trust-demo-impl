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

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

/*
 * The WHO HCERT verification facade, from VaxLink's `:hcert`: one Kotlin API over hcert-core, with
 * the trust list cache, the clock and the DVC to FHIR mapper as common code. Android and desktop
 * share the hcert-core adapter through the jvmShared source set.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKotlinMultiplatformLibrary)
  alias(libs.plugins.kotlinSerialization)
  id("spotless-conventions")
}

kotlin {
  jvmToolchain(21)

  applyDefaultHierarchyTemplate {
    common {
      group("jvmShared") {
        withJvm()
        withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
      }
    }
  }

  androidLibrary {
    namespace = "dev.ohs.trustdemo.hcert"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
  }
  jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

  compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

  sourceSets {
    commonMain.dependencies {
      api(libs.ohs.fhir.model.r4)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.datetime)
      implementation(libs.ktor.client.core)
      implementation(project.dependencies.platform(libs.kotlincrypto.hash.bom))
      implementation(libs.kotlincrypto.hash.sha2)
    }
    getByName("jvmSharedMain").dependencies { implementation(libs.hcert.core) }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
    jvmTest.dependencies {
      implementation(libs.ktor.client.mock)
      implementation(libs.hcert.core)
    }
  }
}

kotlin.sourceSets.named("jvmTest") {
  resources.srcDir(rootProject.file("testkit"))
  resources.srcDir(rootProject.file("testkit/who"))
  resources.srcDir(rootProject.file("ig/resources"))
}
