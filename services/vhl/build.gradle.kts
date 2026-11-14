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
plugins {
  alias(libs.plugins.kotlinJvm)
  application
  id("spotless-conventions")
}

kotlin { jvmToolchain(21) }

application { mainClass = "dev.ohs.trustdemo.vhl.MainKt" }

dependencies {
  implementation(libs.hcert.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.server.auth)
  implementation(libs.ktor.server.auth.jwt)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.core)
  runtimeOnly(libs.slf4j.nop)

  testImplementation(libs.kotlin.testJunit)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.ktor.server.testHost)
}
