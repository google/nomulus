// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import java.io.PrintStream;

val enableDependencyLocking: String by project
val allowInsecureProtocol: String by project
val allowInsecure = allowInsecureProtocol

buildscript {
  // We need to do this again within "buildscript" because setting it in the
  // main script doesn't affect build dependencies.
  val enableDependencyLocking: String by project
  if (enableDependencyLocking.toBoolean()) {
    // Lock application dependencies.
    dependencyLocking {
      lockAllConfigurations()
    }
  }
}

plugins {
  // Java static analysis plugins. Keep versions consistent with ../build.gradle

  // We don't anticipate enabling the Gradle lint plugin because they will not support Kotlin
  // See https://github.com/nebula-plugins/gradle-lint-plugin/issues/166
  // id 'nebula.lint' version '16.0.2'
  id("net.ltgt.errorprone") version "3.1.0"
  checkstyle
  id("com.diffplug.spotless") version "6.20.0"
}

checkstyle {
    configDirectory.set(file("../config/checkstyle"))
}

println("enableDependencyLocking is $enableDependencyLocking")
if (enableDependencyLocking.toBoolean()) {
  // Lock application dependencies.
  dependencyLocking {
    lockAllConfigurations()
  }
}

repositories {
  val mavenUrl = (project.ext.properties.get("mavenUrl") ?: "") as String
  if (mavenUrl.isEmpty()) {
    println("Java dependencies: Using Maven central...")
    mavenCentral()
    google()
  } else {
    maven {
      println("Java dependencies: Using repo ${mavenUrl}...")
      url = uri(mavenUrl)
      isAllowInsecureProtocol = allowInsecureProtocol == "true"
    }
  }
}

apply(from = "../dependencies.gradle")
apply(from = "../dependency_lic.gradle")
apply(from = "../java_common.gradle")

// 'listenablefuture' is folded into guava since v32. This block is required
// until all transitive dependencies have upgraded past guava v32.
// TODO(periodically): remove this block and see if build succeeds.
configurations.all {
  resolutionStrategy
      .capabilitiesResolution
      .withCapability("com.google.guava:listenablefuture") {
      select("com.google.guava:guava:0")
  }
}

project.the<SourceSetContainer>()["main"].java {
  srcDir("${project.buildDir}/generated/source/apt/main")
}

dependencies {
  val deps = project.ext["dependencyMap"] as Map<*, *>
  val implementation by configurations
  val testImplementation by configurations
  val annotationProcessor by configurations
  implementation(deps["com.google.auth:google-auth-library-credentials"]!!)
  implementation(deps["com.google.auth:google-auth-library-oauth2-http"]!!)
  implementation(deps["com.google.auto.value:auto-value-annotations"]!!)
  // implementation(deps["com.google.common.html.types:types"]!!)
  implementation(deps["com.google.cloud:google-cloud-core"]!!)
  implementation(deps["com.google.cloud:google-cloud-storage"]!!)
  implementation(deps["com.google.guava:guava"]!!)
  implementation(deps["com.google.protobuf:protobuf-java"]!!)
  implementation(deps["com.google.template:soy"]!!)
  implementation(deps["org.apache.commons:commons-text"]!!)
  annotationProcessor(deps["com.google.auto.value:auto-value"]!!)
  testImplementation(deps["com.google.truth:truth"]!!)
  testImplementation(
      deps["com.google.truth.extensions:truth-java8-extension"]!!)
  testImplementation(deps["org.junit.jupiter:junit-jupiter-api"]!!)
  testImplementation(deps["org.junit.jupiter:junit-jupiter-engine"]!!)
  testImplementation(deps["org.mockito:mockito-core"]!!)
}

gradle.projectsEvaluated {
  tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:unchecked")
  }
}

tasks.register("exportDependencies") {
  doLast {
    project.configurations.forEach {
      println("dependency is $it")
      // it.dependencies.findAll {
      //   it.group != null
      // }.each {
      //   output.println("${it.group}:${it.name}")
      // }
    }
  }
}
