/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java library project to get you started.
 * For more details take a look at the 'Building Java & JVM projects' chapter in the Gradle
 * User Manual available at https://docs.gradle.org/7.4.2/userguide/building_java_projects.html
 */

plugins {
    // Apply the java-library plugin for API and implementation separation.
    `java-library`
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
  implementation("joda-time:joda-time:2.10.14")
  implementation("com.google.truth:truth:1.1.3")
  implementation("io.github.java-diff-utils:java-diff-utils:4.11")
  implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")
  implementation("com.google.guava:guava:31.1-jre")
  implementation("com.google.flogger:flogger:0.7.4")
  implementation(project(":utils"))

  testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}

tasks.compileJava {
     options.release.set(8)
}
