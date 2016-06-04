# -*- mode: python; -*-
#
# Copyright 2016 The Domain Registry Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""External dependencies for Domain Registry."""

def domain_registry_repositories():

  native.maven_jar(
      name = "appengine_api_sdk",
      artifact = "com.google.appengine:appengine-api-1.0-sdk:1.9.30",
      sha1 = "239376bdb4d57e2c2f5b61197ad11cb5eeca6b6c",
  )

  native.maven_jar(
      name = "appengine_api_labs",
      artifact = "com.google.appengine:appengine-api-labs:1.9.30",
      sha1 = "843a35d7bf4bdcf56b95174f33b702348d16b5ff",
  )

  native.maven_jar(
      name = "appengine_api_stubs",
      artifact = "com.google.appengine:appengine-api-stubs:1.9.30",
      sha1 = "f04454ac4dcc3ea7c4a2b12eae63c6998829a1f0",
  )

  native.maven_jar(
      name = "appengine_gcs_client",
      artifact = "com.google.appengine.tools:appengine-gcs-client:0.5",
      sha1 = "5357744d5fe0c5e800afeb079fd234a46e7618f7",
  )

  native.maven_jar(
      name = "appengine_local_endpoints",
      artifact = "com.google.appengine:appengine-local-endpoints:1.9.30",
      sha1 = "c89715aa01247b37ada5a95966edfd2ca065d563",
  )

  native.maven_jar(
      name = "appengine_mapreduce",
      artifact = "com.google.appengine.tools:appengine-mapreduce:0.8.5",
      sha1 = "46e0456540a9fe9006c4accb51c4c8d9a45a77ce",
  )

  native.maven_jar(
      name = "appengine_pipeline",
      artifact = "com.google.appengine.tools:appengine-pipeline:0.2.13",
      sha1 = "2019a2c6acdbc8216161970afac96bb147d07c36",
  )

  native.maven_jar(
      name = "appengine_remote_api",
      artifact = "com.google.appengine:appengine-remote-api:1.9.30",
      sha1 = "3c2ed95f2c06a433c14c9a71efb56c3917bfe856",
  )

  native.maven_jar(
      name = "appengine_testing",
      artifact = "com.google.appengine:appengine-testing:1.9.30",
      sha1 = "dd2e1cb866712ce7666a34131cc7a1464d9e4b4d",
  )

  native.maven_jar(
      name = "appengine_tools_sdk",
      artifact = "com.google.appengine:appengine-tools-sdk:1.9.30",
      sha1 = "794bd339c2b628ef8580e887398981acb28f3e72",
  )

  native.maven_jar(
      name = "auto_common",
      artifact = "com.google.auto:auto-common:0.5",
      sha1 = "27185563ca9551183fa5379807c3034c0012c8c4",
  )

  native.maven_jar(
      name = "auto_factory",
      artifact = "com.google.auto.factory:auto-factory:1.0-beta3",
      sha1 = "99b2ffe0e41abbd4cc42bf3836276e7174c4929d",
  )

  native.maven_jar(
      name = "auto_service",
      artifact = "com.google.auto.service:auto-service:1.0-rc2",
      sha1 = "51033a5b8fcf7039159e35b6878f106ccd5fb35f",
  )

  native.maven_jar(
      name = "auto_value",
      artifact = "com.google.auto.value:auto-value:1.1",
      sha1 = "f6951c141ea3e89c0f8b01da16834880a1ebf162",
  )

  native.maven_jar(
      name = "bcpg_jdk15on",
      artifact = "org.bouncycastle:bcpg-jdk15on:1.52",
      sha1 = "ff4665a4b5633ff6894209d5dd10b7e612291858",
  )

  native.maven_jar(
      name = "bcprov_jdk15on",
      artifact = "org.bouncycastle:bcprov-jdk15on:1.52",
      sha1 = "88a941faf9819d371e3174b5ed56a3f3f7d73269",
  )

  native.maven_jar(
      name = "bcpkix_jdk15on",
      artifact = "org.bouncycastle:bcpkix-jdk15on:1.52",
      sha1 = "b8ffac2bbc6626f86909589c8cc63637cc936504",
  )

  native.maven_jar(
      name = "braintree_java",
      artifact = "com.braintreepayments.gateway:braintree-java:2.54.0",
      sha1 = "b9940196feaf692de32b0d37c55ded76fb9b1ba7",
  )

  native.maven_jar(
      name = "charts4j",
      artifact = "com.googlecode.charts4j:charts4j:1.3",
      sha1 = "80dd3b0d5591580c429b0e2529706f6be5bddc0f",
  )

  native.maven_jar(
      name = "dagger",
      artifact = "com.google.dagger:dagger:2.4",
      sha1 = "6b290a792253035c9fcc912d6a4d7efb3e850211",
  )

  native.maven_jar(
      name = "dagger_compiler",
      artifact = "com.google.dagger:dagger-compiler:2.4",
      sha1 = "01053c9ef441e93088c9261c33163f6af30766b7",
  )

  native.maven_jar(
      name = "dagger_producers",
      artifact = "com.google.dagger:dagger-producers:2.4",
      sha1 = "f334a19afdc2ce2d8d5191f8a0fac2321bdd50fc",
  )

  native.maven_jar(
      name = "dnsjava",
      artifact = "dnsjava:dnsjava:2.1.7",
      sha1 = "0a1ed0a251d22bf528cebfafb94c55e6f3f339cf",
  )

  native.maven_jar(
      name = "eclipse_jdt_core",
      artifact = "org.eclipse.jdt:org.eclipse.jdt.core:3.10.0",
      sha1 = "647e19b28c106a63a14401c0f5956289792adf2f",
  )

  native.maven_jar(
      name = "fastutil",
      artifact = "it.unimi.dsi:fastutil:6.4.3",
      sha1 = "634ae8b497f0326136fd4995618207e48989623b",
  )

  native.maven_jar(
      name = "ftpserver_core",
      artifact = "org.apache.ftpserver:ftpserver-core:1.0.6",
      sha1 = "2ad1570cd6c0d7ea7ca4d3c26a205e02452f5d7d",
  )

  native.maven_jar(
      name = "gdata_core",
      artifact = "com.google.gdata:core:1.47.1",
      sha1 = "52ee0d917c1c3461f6e12079f73ed71bc75f12d4",
  )

  native.maven_jar(
      name = "google_api_client",
      artifact = "com.google.api-client:google-api-client:1.21.0",
      sha1 = "16a6b3c680f3bf7b81bb42790ff5c1b72c5bbedc",
  )

  native.maven_jar(
      name = "google_api_client_appengine",
      artifact = "com.google.api-client:google-api-client-appengine:1.21.0",
      sha1 = "b4246cf952f6c536465bb58727a4037176003602",
  )

  native.maven_jar(
      name = "google_api_services_admin_directory",
      artifact = "com.google.apis:google-api-services-admin-directory:directory_v1-rev50-1.19.1",
      sha1 = "fce75e874bf4e447128d89d0d3a5a594bc713eba",
  )

  native.maven_jar(
      name = "google_api_services_bigquery",
      artifact = "com.google.apis:google-api-services-bigquery:v2-rev154-1.19.0",
      sha1 = "4f1ee62be6b1b7258560ee7808094292798ef718",
  )

  native.maven_jar(
      name = "google_api_services_drive",
      artifact = "com.google.apis:google-api-services-drive:v2-rev160-1.19.1",
      sha1 = "098adf9128428643992ae6fa0878a7f45e7cec7d",
  )

  native.maven_jar(
      name = "google_api_services_storage",
      artifact = "com.google.apis:google-api-services-storage:v1-rev15-1.19.0",
      sha1 = "91f40f13ab4c24ac33d505695433ba842690bf40",
  )

  native.maven_jar(
      name = "google_api_services_groupssettings",
      artifact = "com.google.apis:google-api-services-groupssettings:v1-rev54-1.19.1",
      sha1 = "28a658b76985b151fb80ce0429e04df4e0095b26",
  )

  native.maven_jar(
      name = "google_http_client",
      artifact = "com.google.http-client:google-http-client:1.21.0",
      sha1 = "42631630fe1276d4d6d6397bb07d53a4e4fec278",
  )

  native.maven_jar(
      name = "google_http_client_appengine",
      artifact = "com.google.http-client:google-http-client-appengine:1.21.0",
      sha1 = "7244bd3c110b15066f4288baa61e350d6a14120c",
  )

  native.maven_jar(
      name = "google_http_client_jackson2",
      artifact = "com.google.http-client:google-http-client-jackson2:1.21.0",
      sha1 = "8ce17bdd15fff0fd8cf359757f29e778fc7191ad",
  )

  native.maven_jar(
      name = "google_oauth_client",
      artifact = "com.google.oauth-client:google-oauth-client:1.21.0",
      sha1 = "61ec42bbfc51aafde5eb8b4923c602c5b5965bc2",
  )

  native.maven_jar(
      name = "google_oauth_client_appengine",
      artifact = "com.google.oauth-client:google-oauth-client-appengine:1.21.0",
      sha1 = "c11014f06ade0a418b2028df41b17f3b17d9cb21",
  )

  native.maven_jar(
      name = "gson",
      artifact = "com.google.code.gson:gson:2.4",
      sha1 = "0695b63d702f505b9b916e02272e3b6381bade7f",
  )

  native.maven_jar(
      name = "guava",
      artifact = "com.google.guava:guava:19.0",
      sha1 = "6ce200f6b23222af3d8abb6b6459e6c44f4bb0e9",
  )

  native.maven_jar(
      name = "guava_testlib",
      artifact = "com.google.guava:guava-testlib:19.0",
      sha1 = "ce5b880b206de3f76d364988a6308c68c726f74a",
  )

  native.maven_jar(
      name = "hamcrest_core",
      artifact = "org.hamcrest:hamcrest-core:1.3",
      sha1 = "42a25dc3219429f0e5d060061f71acb49bf010a0",
  )

  native.maven_jar(
      name = "hamcrest_library",
      artifact = "org.hamcrest:hamcrest-library:1.3",
      sha1 = "4785a3c21320980282f9f33d0d1264a69040538f",
  )

  native.maven_jar(
      name = "icu4j",
      artifact = "com.ibm.icu:icu4j:56.1",
      sha1 = "8dd6671f52165a0419e6de5e1016400875a90fa9",
  )

  native.maven_jar(
      name = "jackson_core",
      artifact = "com.fasterxml.jackson.core:jackson-core:2.5.1",
      sha1 = "e2a00ad1d7e540ec395e9296a34da484c8888d4d",
  )

  native.maven_jar(
      name = "jackson_databind",
      artifact = "com.fasterxml.jackson.core:jackson-databind:2.5.1",
      sha1 = "5e57baebad3898aca8a825adaf2be6fd189442f2",
  )

  native.maven_jar(
      name = "javapoet",
      artifact = "com.squareup:javapoet:1.5.1",
      sha1 = "1d36b86b8fecbe64ea38aea741599720cb07b7d2",
  )

  native.maven_jar(
      name = "javawriter",
      artifact = "com.squareup:javawriter:2.5.1",
      sha1 = "54c87b3d91238e5b58e1a436d4916eee680ec959",
  )

  native.maven_jar(
      name = "jaxb_api",
      artifact = "javax.xml.bind:jaxb-api:2.2.12",
      sha1 = "4c83805595b15acf41d71d49e3add7c0e85baaed",
  )

  native.maven_jar(
      name = "jaxb_core",
      artifact = "com.sun.xml.bind:jaxb-core:2.2.11",
      sha1 = "c3f87d654f8d5943cd08592f3f758856544d279a",
  )

  native.maven_jar(
      name = "jaxb_impl",
      artifact = "com.sun.xml.bind:jaxb-impl:2.2.11",
      sha1 = "a49ce57aee680f9435f49ba6ef427d38c93247a6",
  )

  native.maven_jar(
      name = "jaxb_xjc",
      artifact = "com.sun.xml.bind:jaxb-xjc:2.2.11",
      sha1 = "f099cedb9b245323f906ab9f75adc48cef305cfd",
  )

  native.maven_jar(
      name = "jcommander",
      artifact = "com.beust:jcommander:1.48",
      sha1 = "bfcb96281ea3b59d626704f74bc6d625ff51cbce",
  )

  native.maven_jar(
      name = "jetty",
      artifact = "org.mortbay.jetty:jetty:6.1.22",
      sha1 = "e097b3b684cececf84a35cfdd08e56096a3188da",
  )

  native.maven_jar(
      name = "jetty_util",
      artifact = "org.mortbay.jetty:jetty-util:6.1.22",
      sha1 = "9039d1940a9ae1c91d2b5d7fdfd64bd1924cd447",
  )

  native.maven_jar(
      name = "joda_money",
      artifact = "org.joda:joda-money:0.10.0",
      sha1 = "4056712d2e6db043a38b78c4ee2130c74bae7216",
  )

  native.maven_jar(
      name = "joda_time",
      artifact = "joda-time:joda-time:2.3",
      sha1 = "56498efd17752898cfcc3868c1b6211a07b12b8f",
  )

  native.maven_jar(
      name = "json",
      artifact = "org.json:json:20090211",
      sha1 = "c183aa3a2a6250293808bba12262c8920ce5a51c",
  )

  native.maven_jar(
      name = "json_simple",
      artifact = "com.googlecode.json-simple:json-simple:1.1.1",
      sha1 = "c9ad4a0850ab676c5c64461a05ca524cdfff59f1",
  )

  native.maven_jar(
      name = "jsr305",
      artifact = "com.google.code.findbugs:jsr305:1.3.9",
      sha1 = "40719ea6961c0cb6afaeb6a921eaa1f6afd4cfdf",
  )

  native.maven_jar(
      name = "jsr330_inject",
      artifact = "javax.inject:javax.inject:1",
      sha1 = "6975da39a7040257bd51d21a231b76c915872d38",
  )

  native.maven_jar(
      name = "junit",
      artifact = "junit:junit:4.11",
      sha1 = "4e031bb61df09069aeb2bffb4019e7a5034a4ee0",
  )

  native.maven_jar(
      name = "jzlib",
      artifact = "com.jcraft:jzlib:1.1.3",
      sha1 = "c01428efa717624f7aabf4df319939dda9646b2d",
  )

  native.maven_jar(
      name = "mina_core",
      artifact = "org.apache.mina:mina-core:2.0.0",
      sha1 = "4ae3550e925c2621eca3ef9fb4de5298d6f91cc4",
  )

  native.maven_jar(
      name = "mockito",
      artifact = "org.mockito:mockito-all:1.9.5",
      sha1 = "79a8984096fc6591c1e3690e07d41be506356fa5",
  )

  native.maven_jar(
      name = "protobuf_java",
      artifact = "com.google.protobuf:protobuf-java:2.6.1",
      sha1 = "d9521f2aecb909835746b7a5facf612af5e890e8",
  )

  native.maven_jar(
      name = "qdox",
      artifact = "com.thoughtworks.qdox:qdox:1.12.1",
      sha1 = "f7122f6ab1f64bdf9f5970b0e89bfb355e036897",
  )

  native.maven_jar(
      name = "re2j",
      artifact = "com.google.re2j:re2j:1.1",
      sha1 = "d716952ab58aa4369ea15126505a36544d50a333",
  )

  native.maven_jar(
      name = "servlet_api",
      artifact = "org.apache.tomcat:servlet-api:6.0.20",
      sha1 = "230937c21f1e2da527bf5ebb13f28bab6b2f3849",
  )

  native.maven_jar(
      name = "slf4j_api",
      artifact = "org.slf4j:slf4j-api:1.7.14",
      sha1 = "862a5bc736005d68727d1387844d09d62efdb6cb",
  )

  native.maven_jar(
      name = "sshd_core",
      artifact = "org.apache.sshd:sshd-core:0.7.0",
      sha1 = "ef5d0cae23845dead3fc53ecd61bd990ed42f60f",
  )

  native.maven_jar(
      name = "truth",
      artifact = "com.google.truth:truth:0.28",
      sha1 = "0a388c7877c845ff4b8e19689dda5ac9d34622c4",
  )

  native.maven_jar(
      name = "stax2_api",
      artifact = "org.codehaus.woodstox:stax2-api:3.1.4",
      sha1 = "ac19014b1e6a7c08aad07fe114af792676b685b7",
  )
