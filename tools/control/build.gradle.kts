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

application {
  mainClass = "dev.ohs.trustdemo.control.MainKt"
  applicationName = "trust-demo"
}

distributions.main { distributionBaseName = "control" }

val igDir = rootProject.file("ig/resources")
val igIndexDir = layout.buildDirectory.dir("generated/ig-index")

val igIndex =
  tasks.register("igIndex") {
    val source = igDir
    val target = igIndexDir.map { it.file("ig-index.txt").asFile }
    inputs.dir(source)
    outputs.file(target)
    doLast {
      val names = source.listFiles { file -> file.extension == "json" }.map { it.name }.sorted()
      target.get().apply {
        parentFile.mkdirs()
        writeText(names.joinToString("\n"))
      }
    }
  }

sourceSets.main { resources.srcDirs(igDir, files(igIndexDir).builtBy(igIndex)) }

dependencies {
  implementation(libs.clikt)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.core)
  api(libs.ohs.fhir.model.r4)
  implementation(libs.kotlinx.serialization.json)
  runtimeOnly(libs.slf4j.nop)

  testImplementation(libs.kotlin.testJunit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.client.mock)
}
