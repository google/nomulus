# Copyright 2017 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""External dependencies for Nomulus."""


load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@io_bazel_rules_closure//closure/private:java_import_external.bzl", "java_import_external")

def domain_registry_bazel_check():
    """Checks Bazel version for Nomulus."""
    _check_bazel_version("Nomulus", "0.15.0")

def domain_registry_repositories(
        omit_com_beust_jcommander = False,
        omit_com_fasterxml_jackson_core = False,
        omit_com_fasterxml_jackson_core_jackson_annotations = False,
        omit_com_fasterxml_jackson_core_jackson_databind = False,
        omit_com_google_api_client = False,
        omit_com_google_api_client_appengine = False,
        omit_com_google_api_client_jackson2 = False,
        omit_com_google_api_client_java6 = False,
        omit_com_google_api_client_servlet = False,
        omit_com_google_apis_google_api_services_appengine = False,
        omit_com_google_apis_google_api_services_admin_directory = False,
        omit_com_google_apis_google_api_services_bigquery = False,
        omit_com_google_apis_google_api_services_clouddebugger = False,
        omit_com_google_apis_google_api_services_cloudkms = False,
        omit_com_google_apis_google_api_services_cloudresourcemanager = False,
        omit_com_google_apis_google_api_services_dataflow = False,
        omit_com_google_apis_google_api_services_dns = False,
        omit_com_google_apis_google_api_services_drive = False,
        omit_com_google_apis_google_api_services_groupssettings = False,
        omit_com_google_apis_google_api_services_monitoring = False,
        omit_com_google_apis_google_api_services_sheets = False,
        omit_com_google_apis_google_api_services_storage = False,
        omit_com_google_appengine_api_1_0_sdk = False,
        omit_com_google_appengine_api_labs = False,
        omit_com_google_appengine_api_stubs = False,
        omit_com_google_appengine_remote_api = False,
        omit_com_google_appengine_testing = False,
        omit_com_google_appengine_tools_appengine_gcs_client = False,
        omit_com_google_appengine_tools_appengine_mapreduce = False,
        omit_com_google_appengine_tools_appengine_pipeline = False,
        omit_com_google_appengine_tools_sdk = False,
        omit_com_google_auth_library_credentials = False,
        omit_com_google_auth_library_oauth2_http = False,
        omit_com_google_auto_common = False,
        omit_com_google_auto_factory = False,
        omit_com_google_auto_service = False,
        omit_com_google_auto_value = False,
        omit_com_google_gson = False,
        omit_com_google_cloud_bigdataoss_gcsio = False,
        omit_com_google_cloud_bigdataoss_util = False,
        omit_com_google_code_findbugs_jsr305 = False,
        omit_com_google_dagger = False,
        omit_com_google_dagger_compiler = False,
        omit_com_google_dagger_producers = False,
        omit_com_google_errorprone_error_prone_annotations = False,
        omit_com_google_errorprone_javac_shaded = False,
        omit_com_google_flogger = False,
        omit_com_google_flogger_system_backend = False,
        omit_com_google_gdata_core = False,
        omit_com_google_googlejavaformat_google_java_format = False,
        omit_com_google_guava = False,
        omit_com_google_guava_failureaccess = False,
        omit_com_google_guava_listenablefuture = False,
        omit_com_google_guava_testlib = False,
        omit_com_google_gwt_user = False,
        omit_com_google_http_client = False,
        omit_com_google_http_client_appengine = False,
        omit_com_google_http_client_jackson2 = False,
        omit_com_google_monitoring_client_metrics = False,
        omit_com_google_monitoring_client_stackdriver = False,
        omit_com_google_monitoring_client_contrib = False,
        omit_com_google_oauth_client = False,
        omit_com_google_oauth_client_appengine = False,
        omit_com_google_oauth_client_java6 = False,
        omit_com_google_oauth_client_jetty = False,
        omit_com_google_oauth_client_servlet = False,
        omit_com_google_protobuf = False,
        omit_com_google_protobuf_java = False,
        omit_com_google_re2j = False,
        omit_com_google_template_soy = False,
        omit_com_google_truth = False,
        omit_com_google_truth_extensions_truth_java8_extension = False,
        omit_com_googlecode_java_diff_utils_diffutils = False,
        omit_com_googlecode_charts4j = False,
        omit_com_googlecode_json_simple = False,
        omit_com_ibm_icu_icu4j = False,
        omit_com_jcraft_jzlib = False,
        omit_com_squareup_javapoet = False,
        omit_com_squareup_javawriter = False,
        omit_com_sun_xml_bind_jaxb_core = False,
        omit_com_sun_xml_bind_jaxb_impl = False,
        omit_com_sun_xml_bind_jaxb_jxc = False,
        omit_com_sun_xml_bind_jaxb_xjc = False,
        omit_com_thoughtworks_paranamer = False,
        omit_commons_codec = False,
        omit_commons_logging = False,
        omit_dnsjava = False,
        omit_io_netty_buffer = False,
        omit_io_netty_codec = False,
        omit_io_netty_codec_http = False,
        omit_io_netty_common = False,
        omit_io_netty_handler = False,
        omit_io_netty_resolver = False,
        omit_io_netty_tcnative = False,
        omit_io_netty_tcnative_boringssl_static = False,
        omit_io_netty_transport = False,
        omit_it_unimi_dsi_fastutil = False,
        omit_com_sun_activation_javax_activation = False,
        omit_javax_annotation_jsr250_api = False,
        omit_javax_inject = False,
        omit_javax_mail = False,
        omit_javax_servlet_api = False,
        omit_javax_validation_api = False,
        omit_javax_xml_bind_jaxb_api = False,
        omit_javax_xml_soap_api = False,
        omit_javax_xml_ws_jaxws_api = False,
        omit_jline = False,
        omit_joda_time = False,
        omit_junit = False,
        omit_net_bytebuddy = False,
        omit_org_apache_avro = False,
        omit_org_apache_beam_runners_direct_java = False,
        omit_org_apache_beam_runners_google_cloud_dataflow_java = False,
        omit_org_apache_beam_sdks_common_runner_api = False,
        omit_org_apache_beam_sdks_java_core = False,
        omit_org_apache_beam_sdks_java_extensions_google_cloud_platform_core = False,
        omit_org_apache_beam_sdks_java_io_google_cloud_platform = False,
        omit_org_apache_commons_compress = False,
        omit_org_apache_ftpserver_core = False,
        omit_org_apache_httpcomponents_httpclient = False,
        omit_org_apache_httpcomponents_httpcore = False,
        omit_org_apache_mina_core = False,
        omit_org_apache_sshd_core = False,
        omit_org_apache_sshd_scp = False,
        omit_org_apache_sshd_sftp = False,
        omit_org_apache_tomcat_servlet_api = False,
        omit_org_apache_tomcat_annotations_api = False,
        omit_org_bouncycastle_bcpg_jdk15on = False,
        omit_org_bouncycastle_bcpkix_jdk15on = False,
        omit_org_bouncycastle_bcprov_jdk15on = False,
        omit_org_codehaus_jackson_core_asl = False,
        omit_org_codehaus_jackson_mapper_asl = False,
        omit_org_hamcrest_all = False,
        omit_org_hamcrest_core = False,
        omit_org_hamcrest_library = False,
        omit_org_joda_money = False,
        omit_org_json = False,
        omit_org_khronos_opengl_api = False,
        omit_org_mockito_core = False,
        omit_org_mortbay_jetty = False,
        omit_org_mortbay_jetty_servlet_api = False,
        omit_org_mortbay_jetty_util = False,
        omit_org_objenesis_objenesis = False,
        omit_org_osgi_core = False,
        omit_org_slf4j_api = False,
        omit_org_tukaani_xz = False,
        omit_org_xerial_snappy_java = False,
        omit_org_yaml_snakeyaml = False,
        omit_xerces_xmlParserAPIs = False,
        omit_xpp3 = False):
    """Imports dependencies for Nomulus."""
    domain_registry_bazel_check()
    if not omit_com_beust_jcommander:
        com_beust_jcommander()
    if not omit_com_fasterxml_jackson_core:
        com_fasterxml_jackson_core()
    if not omit_com_fasterxml_jackson_core_jackson_annotations:
        com_fasterxml_jackson_core_jackson_annotations()
    if not omit_com_fasterxml_jackson_core_jackson_databind:
        com_fasterxml_jackson_core_jackson_databind()
    if not omit_com_google_api_client:
        com_google_api_client()
    if not omit_com_google_api_client_appengine:
        com_google_api_client_appengine()
    if not omit_com_google_api_client_jackson2:
        com_google_api_client_jackson2()
    if not omit_com_google_api_client_java6:
        com_google_api_client_java6()
    if not omit_com_google_api_client_servlet:
        com_google_api_client_servlet()
    if not omit_com_google_apis_google_api_services_admin_directory:
        com_google_apis_google_api_services_admin_directory()
    if not omit_com_google_apis_google_api_services_appengine:
        com_google_apis_google_api_services_appengine()
    if not omit_com_google_apis_google_api_services_bigquery:
        com_google_apis_google_api_services_bigquery()
    if not omit_com_google_apis_google_api_services_clouddebugger:
        com_google_apis_google_api_services_clouddebugger()
    if not omit_com_google_apis_google_api_services_cloudkms:
        com_google_apis_google_api_services_cloudkms()
    if not omit_com_google_apis_google_api_services_cloudresourcemanager:
        com_google_apis_google_api_services_cloudresourcemanager()
    if not omit_com_google_apis_google_api_services_dataflow:
        com_google_apis_google_api_services_dataflow()
    if not omit_com_google_apis_google_api_services_dns:
        com_google_apis_google_api_services_dns()
    if not omit_com_google_apis_google_api_services_drive:
        com_google_apis_google_api_services_drive()
    if not omit_com_google_apis_google_api_services_groupssettings:
        com_google_apis_google_api_services_groupssettings()
    if not omit_com_google_apis_google_api_services_monitoring:
        com_google_apis_google_api_services_monitoring()
    if not omit_com_google_apis_google_api_services_sheets:
        com_google_apis_google_api_services_sheets()
    if not omit_com_google_apis_google_api_services_storage:
        com_google_apis_google_api_services_storage()
    if not omit_com_google_appengine_api_1_0_sdk:
        com_google_appengine_api_1_0_sdk()
    if not omit_com_google_appengine_api_labs:
        com_google_appengine_api_labs()
    if not omit_com_google_appengine_api_stubs:
        com_google_appengine_api_stubs()
    if not omit_com_google_appengine_remote_api:
        com_google_appengine_remote_api()
    if not omit_com_google_appengine_testing:
        com_google_appengine_testing()
    if not omit_com_google_appengine_tools_appengine_gcs_client:
        com_google_appengine_tools_appengine_gcs_client()
    if not omit_com_google_appengine_tools_appengine_mapreduce:
        com_google_appengine_tools_appengine_mapreduce()
    if not omit_com_google_appengine_tools_appengine_pipeline:
        com_google_appengine_tools_appengine_pipeline()
    if not omit_com_google_appengine_tools_sdk:
        com_google_appengine_tools_sdk()
    if not omit_com_google_auth_library_credentials:
        com_google_auth_library_credentials()
    if not omit_com_google_auth_library_oauth2_http:
        com_google_auth_library_oauth2_http()
    if not omit_com_google_auto_common:
        com_google_auto_common()
    if not omit_com_google_auto_factory:
        com_google_auto_factory()
    if not omit_com_google_auto_service:
        com_google_auto_service()
    if not omit_com_google_auto_value:
        com_google_auto_value()
    if not omit_com_google_gson:
        com_google_gson()
    if not omit_com_google_cloud_bigdataoss_gcsio:
        com_google_cloud_bigdataoss_gcsio()
    if not omit_com_google_cloud_bigdataoss_util:
        com_google_cloud_bigdataoss_util()
    if not omit_com_google_code_findbugs_jsr305:
        com_google_code_findbugs_jsr305()
    if not omit_com_google_dagger:
        com_google_dagger()
    if not omit_com_google_dagger_compiler:
        com_google_dagger_compiler()
    if not omit_com_google_dagger_producers:
        com_google_dagger_producers()
    if not omit_com_google_errorprone_error_prone_annotations:
        com_google_errorprone_error_prone_annotations()
    if not omit_com_google_errorprone_javac_shaded:
        com_google_errorprone_javac_shaded()
    if not omit_com_google_flogger:
        com_google_flogger()
    if not omit_com_google_flogger_system_backend:
        com_google_flogger_system_backend()
    if not omit_com_google_gdata_core:
        com_google_gdata_core()
    if not omit_com_google_googlejavaformat_google_java_format:
        com_google_googlejavaformat_google_java_format()
    if not omit_com_google_guava:
        com_google_guava()
    if not omit_com_google_guava_failureaccess:
        com_google_guava_failureaccess()
    if not omit_com_google_guava_listenablefuture:
        com_google_guava_listenablefuture()
    if not omit_com_google_guava_testlib:
        com_google_guava_testlib()
    if not omit_com_google_gwt_user:
        com_google_gwt_user()
    if not omit_com_google_http_client:
        com_google_http_client()
    if not omit_com_google_http_client_appengine:
        com_google_http_client_appengine()
    if not omit_com_google_http_client_jackson2:
        com_google_http_client_jackson2()
    if not omit_com_google_monitoring_client_metrics:
        com_google_monitoring_client_metrics()
    if not omit_com_google_monitoring_client_stackdriver:
        com_google_monitoring_client_stackdriver()
    if not omit_com_google_monitoring_client_contrib:
        com_google_monitoring_client_contrib()
    if not omit_com_google_oauth_client:
        com_google_oauth_client()
    if not omit_com_google_oauth_client_appengine:
        com_google_oauth_client_appengine()
    if not omit_com_google_oauth_client_java6:
        com_google_oauth_client_java6()
    if not omit_com_google_oauth_client_jetty:
        com_google_oauth_client_jetty()
    if not omit_com_google_oauth_client_servlet:
        com_google_oauth_client_servlet()
    if not omit_com_google_protobuf:
        com_google_protobuf()
    if not omit_com_google_protobuf_java:
        com_google_protobuf_java()
    if not omit_com_google_re2j:
        com_google_re2j()
    if not omit_com_google_template_soy:
        com_google_template_soy()
    if not omit_com_google_truth:
        com_google_truth()
    if not omit_com_google_truth_extensions_truth_java8_extension:
        com_google_truth_extensions_truth_java8_extension()
    if not omit_com_googlecode_java_diff_utils_diffutils:
        com_googlecode_java_diff_utils_diffutils()
    if not omit_com_googlecode_charts4j:
        com_googlecode_charts4j()
    if not omit_com_googlecode_json_simple:
        com_googlecode_json_simple()
    if not omit_com_ibm_icu_icu4j:
        com_ibm_icu_icu4j()
    if not omit_com_jcraft_jzlib:
        com_jcraft_jzlib()
    if not omit_com_squareup_javapoet:
        com_squareup_javapoet()
    if not omit_com_squareup_javawriter:
        com_squareup_javawriter()
    if not omit_com_sun_xml_bind_jaxb_core:
        com_sun_xml_bind_jaxb_core()
    if not omit_com_sun_xml_bind_jaxb_impl:
        com_sun_xml_bind_jaxb_impl()
    if not omit_com_sun_xml_bind_jaxb_xjc:
        com_sun_xml_bind_jaxb_xjc()
    if not omit_com_sun_xml_bind_jaxb_jxc:
        com_sun_xml_bind_jaxb_jxc()
    if not omit_com_thoughtworks_paranamer:
        com_thoughtworks_paranamer()
    if not omit_commons_codec:
        commons_codec()
    if not omit_commons_logging:
        commons_logging()
    if not omit_dnsjava:
        dnsjava()
    if not omit_io_netty_buffer:
        io_netty_buffer()
    if not omit_io_netty_codec:
        io_netty_codec()
    if not omit_io_netty_codec_http:
        io_netty_codec_http()
    if not omit_io_netty_common:
        io_netty_common()
    if not omit_io_netty_handler:
        io_netty_handler()
    if not omit_io_netty_resolver:
        io_netty_resolver()
    if not omit_io_netty_tcnative:
        io_netty_tcnative()
    if not omit_io_netty_tcnative_boringssl_static:
        io_netty_tcnative_boringssl_static()
    if not omit_io_netty_transport:
        io_netty_transport()
    if not omit_it_unimi_dsi_fastutil:
        it_unimi_dsi_fastutil()
    if not omit_com_sun_activation_javax_activation:
        com_sun_activation_javax_activation()
    if not omit_javax_annotation_jsr250_api:
        javax_annotation_jsr250_api()
    if not omit_javax_inject:
        javax_inject()
    if not omit_javax_mail:
        javax_mail()
    if not omit_javax_servlet_api:
        javax_servlet_api()
    if not omit_javax_validation_api:
        javax_validation_api()
    if not omit_javax_xml_bind_jaxb_api:
        javax_xml_bind_jaxb_api()
    if not omit_javax_xml_soap_api:
        javax_xml_soap_api()
    if not omit_jline:
        jline()
    if not omit_joda_time:
        joda_time()
    if not omit_junit:
        junit()
    if not omit_net_bytebuddy:
        net_bytebuddy()
    if not omit_org_apache_avro:
        org_apache_avro()
    if not omit_org_apache_beam_runners_direct_java:
        org_apache_beam_runners_direct_java()
    if not omit_org_apache_beam_runners_google_cloud_dataflow_java:
        org_apache_beam_runners_google_cloud_dataflow_java()
    if not omit_org_apache_beam_sdks_common_runner_api:
        org_apache_beam_sdks_common_runner_api()
    if not omit_org_apache_beam_sdks_java_core:
        org_apache_beam_sdks_java_core()
    if not omit_org_apache_beam_sdks_java_extensions_google_cloud_platform_core:
        org_apache_beam_sdks_java_extensions_google_cloud_platform_core()
    if not omit_org_apache_beam_sdks_java_io_google_cloud_platform:
        org_apache_beam_sdks_java_io_google_cloud_platform()
    if not omit_org_apache_commons_compress:
        org_apache_commons_compress()
    if not omit_org_apache_ftpserver_core:
        org_apache_ftpserver_core()
    if not omit_org_apache_httpcomponents_httpclient:
        org_apache_httpcomponents_httpclient()
    if not omit_org_apache_httpcomponents_httpcore:
        org_apache_httpcomponents_httpcore()
    if not omit_org_apache_mina_core:
        org_apache_mina_core()
    if not omit_org_apache_sshd_core:
        org_apache_sshd_core()
    if not omit_org_apache_sshd_scp:
        org_apache_sshd_scp()
    if not omit_org_apache_sshd_sftp:
        org_apache_sshd_sftp()
    if not omit_org_apache_tomcat_servlet_api:
        org_apache_tomcat_servlet_api()
    if not omit_org_apache_tomcat_annotations_api:
        org_apache_tomcat_annotations_api()
    if not omit_org_bouncycastle_bcpg_jdk15on:
        org_bouncycastle_bcpg_jdk15on()
    if not omit_org_bouncycastle_bcpkix_jdk15on:
        org_bouncycastle_bcpkix_jdk15on()
    if not omit_org_bouncycastle_bcprov_jdk15on:
        org_bouncycastle_bcprov_jdk15on()
    if not omit_org_codehaus_jackson_core_asl:
        org_codehaus_jackson_core_asl()
    if not omit_org_codehaus_jackson_mapper_asl:
        org_codehaus_jackson_mapper_asl()
    if not omit_org_hamcrest_all:
        org_hamcrest_all()
    if not omit_org_hamcrest_core:
        org_hamcrest_core()
    if not omit_org_hamcrest_library:
        org_hamcrest_library()
    if not omit_org_joda_money:
        org_joda_money()
    if not omit_org_json:
        org_json()
    if not omit_org_khronos_opengl_api:
        org_khronos_opengl_api()
    if not omit_org_mockito_core:
        org_mockito_core()
    if not omit_org_mortbay_jetty:
        org_mortbay_jetty()
    if not omit_org_mortbay_jetty_servlet_api:
        org_mortbay_jetty_servlet_api()
    if not omit_org_mortbay_jetty_util:
        org_mortbay_jetty_util()
    if not omit_org_objenesis_objenesis:
        org_objenesis_objenesis()
    if not omit_org_osgi_core:
        org_osgi_core()
    if not omit_org_slf4j_api:
        org_slf4j_api()
    if not omit_org_tukaani_xz:
        org_tukaani_xz()
    if not omit_org_xerial_snappy_java:
        org_xerial_snappy_java()
    if not omit_org_yaml_snakeyaml:
        org_yaml_snakeyaml()
    if not omit_xerces_xmlParserAPIs:
        xerces_xmlParserAPIs()
    if not omit_xpp3:
        xpp3()

def com_beust_jcommander():
    java_import_external(
        name = "com_beust_jcommander",
        jar_sha256 = "a7313fcfde070930e40ec79edf3c5948cf34e4f0d25cb3a09f9963d8bdd84113",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/beust/jcommander/1.48/jcommander-1.48.jar",
            "http://repo1.maven.org/maven2/com/beust/jcommander/1.48/jcommander-1.48.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
    )

def com_fasterxml_jackson_core():
    java_import_external(
        name = "com_fasterxml_jackson_core",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "fab8746aedd6427788ee390ea04d438ec141bff7eb3476f8bdd5d9110fb2718a",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar",
            "http://maven.ibiblio.org/maven2/com/fasterxml/jackson/core/jackson-core/2.9.6/jackson-core-2.9.6.jar",
        ],
    )

def com_fasterxml_jackson_core_jackson_annotations():
    java_import_external(
        name = "com_fasterxml_jackson_core_jackson_annotations",
        jar_sha256 = "e61b7343aceeb6ecda291d4ef133cd3e765f178c631c357ffd081abab7f15db8",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.8.0/jackson-annotations-2.8.0.jar",
            "http://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.8.0/jackson-annotations-2.8.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
    )

def com_fasterxml_jackson_core_jackson_databind():
    java_import_external(
        name = "com_fasterxml_jackson_core_jackson_databind",
        jar_sha256 = "2ed1d9d9ad732093bbe9f2c23f7d143c35c092ccc48f1754f23d031f8de2436e",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.8.5/jackson-databind-2.8.5.jar",
            "http://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.8.5/jackson-databind-2.8.5.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@com_fasterxml_jackson_core_jackson_annotations",
            "@com_fasterxml_jackson_core",
        ],
    )

def com_google_api_client():
    java_import_external(
        name = "com_google_api_client",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "fd1f06bc8cea64cd6e85e7a29dd632ba05c4e4ec2daae9a7115b6dbc9004fcd9",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/api-client/google-api-client/1.27.0/google-api-client-1.27.0.jar",
            "http://repo1.maven.org/maven2/com/google/api-client/google-api-client/1.27.0/google-api-client-1.27.0.jar",
        ],
        deps = [
            "@com_google_oauth_client",
            "@com_google_http_client_jackson2",
            "@commons_codec",
            "@com_google_guava",
        ],
    )

def com_google_api_client_appengine():
    java_import_external(
        name = "com_google_api_client_appengine",
        jar_sha256 = "3b6f69bea556806e96ca6d443440968f848058c7956d945abea625e22b1d3fac",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/api-client/google-api-client-appengine/1.22.0/google-api-client-appengine-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/api-client/google-api-client-appengine/1.22.0/google-api-client-appengine-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@com_google_appengine_api_1_0_sdk",
            "@com_google_oauth_client_appengine",
            "@com_google_api_client",
            "@com_google_api_client_servlet",
            "@com_google_http_client_appengine",
        ],
    )

def com_google_api_client_jackson2():
    java_import_external(
        name = "com_google_api_client_jackson2",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "b86e3bdd3b6504741b90de51f06b2236cedfedd0069f942b22adb0b60553de4a",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/api-client/google-api-client-jackson2/1.20.0/google-api-client-jackson2-1.20.0.jar",
            "http://repo1.maven.org/maven2/com/google/api-client/google-api-client-jackson2/1.20.0/google-api-client-jackson2-1.20.0.jar",
        ],
        deps = [
            "@com_google_api_client",
            "@com_google_http_client_jackson2",
        ],
    )

def com_google_monitoring_client_metrics():
    java_import_external(
        name = "com_google_monitoring_client_metrics",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "19c180f9009bda2dd4c3d4a3a8376b1d4d12a319944a916484d3d8d8701303e1",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/monitoring-client/metrics/1.0.4/metrics-1.0.4.jar",
            "http://repo1.maven.org/maven2/com/google/monitoring-client/metrics/1.0.4/metrics-1.0.4.jar",
        ],
        deps = [
            "@com_google_guava",
            "@com_google_auto_value",
            "@com_google_errorprone_error_prone_annotations",
            "@com_google_re2j",
        ],
    )

def com_google_monitoring_client_stackdriver():
    java_import_external(
        name = "com_google_monitoring_client_stackdriver",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "13a1d8fb103b37df9ebed7dee813dcb5019369c46e7790a09a5b6ebc04eef604",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/monitoring-client/stackdriver/1.0.4/stackdriver-1.0.4.jar",
            "http://repo1.maven.org/maven2/com/google/monitoring-client/stackdriver/1.0.4/stackdriver-1.0.4.jar",
        ],
        deps = [
            "@com_google_guava",
            "@com_google_apis_google_api_services_monitoring",
            "@com_google_monitoring_client_metrics",
        ],
    )

def com_google_monitoring_client_contrib():
    java_import_external(
        name = "com_google_monitoring_client_contrib",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "f655f9007065032a6433397fb0141bfc2850dfcc4680f302faca341a007b6264",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/monitoring-client/contrib/1.0.4/contrib-1.0.4.jar",
            "http://repo1.maven.org/maven2/com/google/monitoring-client/contrib/1.0.4/contrib-1.0.4.jar",
        ],
        testonly_ = True,
        deps = [
            "@com_google_truth",
            "@com_google_monitoring_client_metrics",
        ],
    )

def com_google_api_client_java6():
    java_import_external(
        name = "com_google_api_client_java6",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "056ef35bafebd2e2b27817be00aa08e79d24fd4ba1c7c70c2407fd2ec9582cb5",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/api-client/google-api-client-java6/1.27.0/google-api-client-java6-1.27.0.jar",
            "http://repo1.maven.org/maven2/com/google/api-client/google-api-client-java6/1.27.0/google-api-client-java6-1.27.0.jar",
        ],
        deps = [
            "@com_google_api_client",
            "@com_google_oauth_client_java6",
        ],
    )

def com_google_api_client_servlet():
    java_import_external(
        name = "com_google_api_client_servlet",
        jar_sha256 = "66cf62e2ecd7ae73c3dbf4713850e8ff5e5bb0bcaac61243bb0034fa28b2681c",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/api-client/google-api-client-servlet/1.22.0/google-api-client-servlet-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/api-client/google-api-client-servlet/1.22.0/google-api-client-servlet-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@com_google_oauth_client_servlet",
            "@com_google_api_client",
            "@javax_servlet_api",
        ],
    )

def com_google_apis_google_api_services_admin_directory():
    java_import_external(
        name = "com_google_apis_google_api_services_admin_directory",
        jar_sha256 = "c1455436b318d16d665ed0ff305b03eb177542d8a74cfb8b7c1e9bfed5227640",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-admin-directory/directory_v1-rev72-1.22.0/google-api-services-admin-directory-directory_v1-rev72-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-admin-directory/directory_v1-rev72-1.22.0/google-api-services-admin-directory-directory_v1-rev72-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_appengine():
    java_import_external(
        name = "com_google_apis_google_api_services_appengine",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "15329545770163aec4f2bb0c37949a03667f06e012e2204ede22a0c2fb8f9f21",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-appengine/v1-rev85-1.25.0/google-api-services-appengine-v1-rev85-1.25.0.jar",
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-appengine/v1-rev85-1.25.0/google-api-services-appengine-v1-rev85-1.25.0.jar",
        ],
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_bigquery():
    java_import_external(
        name = "com_google_apis_google_api_services_bigquery",
        jar_sha256 = "a8659f00301b34292878f288bc3604c5763d51cb6b82c956a46bbf5b46d8f3f0",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-bigquery/v2-rev325-1.22.0/google-api-services-bigquery-v2-rev325-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-bigquery/v2-rev325-1.22.0/google-api-services-bigquery-v2-rev325-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_clouddebugger():
    java_import_external(
        name = "com_google_apis_google_api_services_clouddebugger",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "5b8dddc70bb63aa373dcf1dc35ec79444c5feb77417b3f83ebe30ec1e9305e47",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-clouddebugger/v2-rev8-1.22.0/google-api-services-clouddebugger-v2-rev8-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-clouddebugger/v2-rev8-1.22.0/google-api-services-clouddebugger-v2-rev8-1.22.0.jar",
        ],
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_cloudkms():
    java_import_external(
        name = "com_google_apis_google_api_services_cloudkms",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "c73854bdbd67c26f030eda346c65fc68e5b252a54f662af1a041caea77333ba4",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-cloudkms/v1-rev12-1.22.0/google-api-services-cloudkms-v1-rev12-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-cloudkms/v1-rev12-1.22.0/google-api-services-cloudkms-v1-rev12-1.22.0.jar",
        ],
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_cloudresourcemanager():
    java_import_external(
        name = "com_google_apis_google_api_services_cloudresourcemanager",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "88ebb190d354afaa26f6dc1739e8c713ca2591131d72fe7bb14e670b3f23cacb",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-cloudresourcemanager/v1-rev6-1.22.0/google-api-services-cloudresourcemanager-v1-rev6-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-cloudresourcemanager/v1-rev6-1.22.0/google-api-services-cloudresourcemanager-v1-rev6-1.22.0.jar",
        ],
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_dataflow():
    java_import_external(
        name = "com_google_apis_google_api_services_dataflow",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "c990c200a48fec60cf11dc146c49ca2f6e865748b0f900ab32fcb4b3341d8f38",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-dataflow/v1b3-rev196-1.22.0/google-api-services-dataflow-v1b3-rev196-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-dataflow/v1b3-rev196-1.22.0/google-api-services-dataflow-v1b3-rev196-1.22.0.jar",
        ],
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_dns():
    java_import_external(
        name = "com_google_apis_google_api_services_dns",
        jar_sha256 = "70fd3a33fe59a033176feeee8e4e1e380a3939468fc953852843c4f874e7d087",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-dns/v2beta1-rev6-1.22.0/google-api-services-dns-v2beta1-rev6-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-dns/v2beta1-rev6-1.22.0/google-api-services-dns-v2beta1-rev6-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_drive():
    java_import_external(
        name = "com_google_apis_google_api_services_drive",
        jar_sha256 = "8894c1ac3bbf723c493c83aca1f786cd6acd8a833a3f1c31394bcc484b6916e4",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-drive/v2-rev160-1.19.1/google-api-services-drive-v2-rev160-1.19.1.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-drive/v2-rev160-1.19.1/google-api-services-drive-v2-rev160-1.19.1.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_groupssettings():
    java_import_external(
        name = "com_google_apis_google_api_services_groupssettings",
        jar_sha256 = "bb06f971362b1b78842f7ec71ae28adf3b31132c97d9cf786645bb2468d56b46",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-groupssettings/v1-rev60-1.22.0/google-api-services-groupssettings-v1-rev60-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-groupssettings/v1-rev60-1.22.0/google-api-services-groupssettings-v1-rev60-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_monitoring():
    java_import_external(
        name = "com_google_apis_google_api_services_monitoring",
        jar_sha256 = "8943d11779280ba90e57245d657368a94bb8474e260ad528e31f1e4ee081e1d9",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-monitoring/v3-rev11-1.22.0/google-api-services-monitoring-v3-rev11-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-monitoring/v3-rev11-1.22.0/google-api-services-monitoring-v3-rev11-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_sheets():
    java_import_external(
        name = "com_google_apis_google_api_services_sheets",
        jar_sha256 = "67529b9efceb1a16b72c6aa0822d50f4f6e8c3c84972a5a37ca6e7bdc19064ba",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-sheets/v4-rev483-1.22.0/google-api-services-sheets-v4-rev483-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-sheets/v4-rev483-1.22.0/google-api-services-sheets-v4-rev483-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = ["@com_google_api_client"],
    )

def com_google_apis_google_api_services_storage():
    java_import_external(
        name = "com_google_apis_google_api_services_storage",
        jar_sha256 = "3a6c857e409a4398ada630124ca52222582beba04943c3cd7c5c76aee0854fcf",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/apis/google-api-services-storage/v1-rev86-1.22.0/google-api-services-storage-v1-rev86-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/apis/google-api-services-storage/v1-rev86-1.22.0/google-api-services-storage-v1-rev86-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = ["@com_google_api_client"],
    )

def com_google_appengine_api_1_0_sdk():
    java_import_external(
        name = "com_google_appengine_api_1_0_sdk",
        jar_sha256 = "c151aee2a0eb5ede2f051861f420da023d437e7b275b50a9f2fe42c893ba693b",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/appengine/appengine-api-1.0-sdk/1.9.48/appengine-api-1.0-sdk-1.9.48.jar",
            "http://repo1.maven.org/maven2/com/google/appengine/appengine-api-1.0-sdk/1.9.48/appengine-api-1.0-sdk-1.9.48.jar",
        ],
        # Google App Engine Terms of Service: https://cloud.google.com/terms/
        # + Shaded Jackson: Apache 2.0
        # + Shaded Antlr: BSD
        # + Shaded Apache Commons Codec: Apache 2.0
        # + Shaded Joda Time: Apache 2.0
        # + Shaded Apache Geronimo: Apache 2.0
        licenses = ["notice"],
        neverlink = True,
        generated_linkable_rule_name = "link",
        extra_build_file_content = "\n".join([
            "java_import(",
            "    name = \"testonly\",",
            "    jars = [\"appengine-api-1.0-sdk-1.9.48.jar\"],",
            "    testonly = True,",
            ")",
        ]),
    )

def com_google_appengine_api_labs():
    java_import_external(
        name = "com_google_appengine_api_labs",
        jar_sha256 = "adb9e0e778ca0911c08c7715e0769c487a5e56d7a984a4e17e26044b5c1fc1be",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/appengine/appengine-api-labs/1.9.48/appengine-api-labs-1.9.48.jar",
            "http://repo1.maven.org/maven2/com/google/appengine/appengine-api-labs/1.9.48/appengine-api-labs-1.9.48.jar",
        ],
        licenses = ["permissive"],  # Google App Engine Terms of Service: https://cloud.google.com/terms/
    )

def com_google_appengine_api_stubs():
    java_import_external(
        name = "com_google_appengine_api_stubs",
        jar_sha256 = "49c5a9228477c2ed589f62e2430a4c029464adf166a8b50c260ad35171b0d763",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/appengine/appengine-api-stubs/1.9.48/appengine-api-stubs-1.9.48.jar",
            "http://repo1.maven.org/maven2/com/google/appengine/appengine-api-stubs/1.9.48/appengine-api-stubs-1.9.48.jar",
        ],
        licenses = ["permissive"],  # Google App Engine Terms of Service: https://cloud.google.com/terms/
    )

def com_google_appengine_remote_api():
    java_import_external(
        name = "com_google_appengine_remote_api",
        jar_sha256 = "6ea6dc3b529038ea6b37e855cd1cd7612f6640feaeb0eec842d4e6d85e1fd052",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/appengine/appengine-remote-api/1.9.48/appengine-remote-api-1.9.48.jar",
            "http://repo1.maven.org/maven2/com/google/appengine/appengine-remote-api/1.9.48/appengine-remote-api-1.9.48.jar",
        ],
        licenses = ["permissive"],  # Google App Engine Terms of Service: https://cloud.google.com/terms/
        neverlink = True,
        generated_linkable_rule_name = "link",
    )

def com_google_appengine_testing():
    java_import_external(
        name = "com_google_appengine_testing",
        jar_sha256 = "f18c93f08b45a56330dcf5625a75e169af37ff1a1d62e0ee4af668c568266fd3",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/appengine/appengine-testing/1.9.58/appengine-testing-1.9.58.jar",
            "http://repo1.maven.org/maven2/com/google/appengine/appengine-testing/1.9.58/appengine-testing-1.9.58.jar",
        ],
        licenses = ["permissive"],  # Google App Engine Terms of Service: https://cloud.google.com/terms/
        testonly_ = True,
        deps = [
            "@com_google_appengine_api_1_0_sdk//:testonly",
            "@com_google_appengine_api_labs",
            "@com_google_appengine_api_stubs",
        ],
    )

def com_google_appengine_tools_appengine_gcs_client():
    java_import_external(
        name = "com_google_appengine_tools_appengine_gcs_client",
        jar_sha256 = "99daa975012ac2f1509ecff1e70c9ec1e5eb435a499d3f381c88bb944739c7d8",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/appengine/tools/appengine-gcs-client/0.6/appengine-gcs-client-0.6.jar",
            "http://repo1.maven.org/maven2/com/google/appengine/tools/appengine-gcs-client/0.6/appengine-gcs-client-0.6.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@com_google_appengine_api_1_0_sdk",
            "@com_google_guava",
            "@joda_time",
            "@com_google_apis_google_api_services_storage",
            "@com_google_api_client_appengine",
            "@com_google_http_client",
            "@com_google_http_client_jackson2",
        ],
    )

def com_google_appengine_tools_appengine_mapreduce():
    java_import_external(
        name = "com_google_appengine_tools_appengine_mapreduce",
        jar_sha256 = "5247f29ad94f422511fb7321a11ffb47bdd6156b00b9b6d7221a4f8f00c4a750",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/appengine/tools/appengine-mapreduce/0.8.5/appengine-mapreduce-0.8.5.jar",
            "http://repo1.maven.org/maven2/com/google/appengine/tools/appengine-mapreduce/0.8.5/appengine-mapreduce-0.8.5.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@com_google_appengine_api_1_0_sdk",
            "@javax_servlet_api",
            "@com_google_appengine_tools_appengine_gcs_client",
            "@com_google_appengine_tools_appengine_pipeline",
            "@com_googlecode_charts4j",
            "@org_json",
            "@com_google_protobuf_java",
            "@com_google_guava",
            "@joda_time",
            "@it_unimi_dsi_fastutil",
            "@com_google_apis_google_api_services_bigquery",
            "@com_google_api_client",
            "@com_google_api_client_appengine",
            "@com_google_http_client",
            "@com_google_http_client_jackson2",
            "@com_fasterxml_jackson_core_jackson_databind",
            "@com_fasterxml_jackson_core",
        ],
    )

def com_google_appengine_tools_appengine_pipeline():
    java_import_external(
        name = "com_google_appengine_tools_appengine_pipeline",
        jar_sha256 = "61da36f73843545db9eaf403112ba14f36a1fa6e685557cff56ce0083d0a7b97",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/appengine/tools/appengine-pipeline/0.2.13/appengine-pipeline-0.2.13.jar",
            "http://repo1.maven.org/maven2/com/google/appengine/tools/appengine-pipeline/0.2.13/appengine-pipeline-0.2.13.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@org_json",
            "@com_google_appengine_api_1_0_sdk",
            "@com_google_guava",
            "@javax_servlet_api",
            "@com_google_appengine_tools_appengine_gcs_client",
        ],
    )

def com_google_appengine_tools_sdk():
    java_import_external(
        name = "com_google_appengine_tools_sdk",
        jar_sha256 = "16acae92eed3a227dda8aae8b456e4058f3c0d30c18859ce0ec872c43101bbdc",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/appengine/appengine-tools-sdk/1.9.48/appengine-tools-sdk-1.9.48.jar",
            "http://repo1.maven.org/maven2/com/google/appengine/appengine-tools-sdk/1.9.48/appengine-tools-sdk-1.9.48.jar",
        ],
        licenses = ["permissive"],  # Google App Engine Terms of Service: https://cloud.google.com/terms/
    )

def com_google_auth_library_credentials():
    java_import_external(
        name = "com_google_auth_library_credentials",
        licenses = ["notice"],  # BSD New license
        jar_sha256 = "df13b1a2d547816e1eaf0cb73c34b85e09a725540f186eb1519de73b15489e9d",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/auth/google-auth-library-credentials/0.7.1/google-auth-library-credentials-0.7.1.jar",
            "http://repo1.maven.org/maven2/com/google/auth/google-auth-library-credentials/0.7.1/google-auth-library-credentials-0.7.1.jar",
        ],
    )

def com_google_auth_library_oauth2_http():
    java_import_external(
        name = "com_google_auth_library_oauth2_http",
        licenses = ["notice"],  # BSD New license
        jar_sha256 = "abc33ebd2411e928f85383b7bd6f33f8ab59dd95d67d363b5af090ffd6adbd98",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/auth/google-auth-library-oauth2-http/0.7.1/google-auth-library-oauth2-http-0.7.1.jar",
            "http://repo1.maven.org/maven2/com/google/auth/google-auth-library-oauth2-http/0.7.1/google-auth-library-oauth2-http-0.7.1.jar",
        ],
        deps = [
            "@com_google_auth_library_credentials",
            "@com_google_http_client",
            "@com_google_http_client_jackson2",
            "@com_google_guava",
        ],
    )

def com_google_auto_common():
    java_import_external(
        name = "com_google_auto_common",
        jar_sha256 = "97db1709f57b91b32edacb596ef4641872f227b7d99ad90e467f0d77f5ba134a",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/auto/auto-common/0.8/auto-common-0.8.jar",
            "http://repo1.maven.org/maven2/com/google/auto/auto-common/0.8/auto-common-0.8.jar",
        ],
        licenses = ["notice"],  # Apache 2.0
        deps = ["@com_google_guava"],
    )

def com_google_auto_factory():
    java_import_external(
        name = "com_google_auto_factory",
        licenses = ["notice"],  # Apache 2.0
        jar_sha256 = "a038e409da90b9e065ec537cce2375b0bb0b07548dca0f9448671b0befb83439",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/auto/factory/auto-factory/1.0-beta3/auto-factory-1.0-beta3.jar",
            "http://repo1.maven.org/maven2/com/google/auto/factory/auto-factory/1.0-beta3/auto-factory-1.0-beta3.jar",
        ],
        # Auto Factory ships its annotations, runtime, and processor in the same
        # jar. The generated code must link against this jar at runtime. So our
        # goal is to introduce as little bloat as possible.The only class we need
        # at runtime is com.google.auto.factory.internal.Preconditions. So we're
        # not going to specify the deps of this jar as part of the java_import().
        generated_rule_name = "jar",
        extra_build_file_content = "\n".join([
            "java_library(",
            "    name = \"processor\",",
            "    exports = [\":jar\"],",
            "    runtime_deps = [",
            "        \"@com_google_auto_common\",",
            "        \"@com_google_guava\",",
            "        \"@com_squareup_javawriter\",",
            "        \"@javax_inject\",",
            "        \"@org_apache_tomcat_annotations_api\",",
            "    ],",
            ")",
            "",
            "java_plugin(",
            "    name = \"AutoFactoryProcessor\",",
            "    output_licenses = [\"unencumbered\"],",
            "    processor_class = \"com.google.auto.factory.processor.AutoFactoryProcessor\",",
            "    generates_api = 1,",
            "    tags = [\"annotation=com.google.auto.factory.AutoFactory;genclass=${package}.${outerclasses}@{className|${classname}Factory}\"],",
            "    deps = [\":processor\"],",
            ")",
            "",
            "java_library(",
            "    name = \"com_google_auto_factory\",",
            "    exported_plugins = [\":AutoFactoryProcessor\"],",
            "    exports = [",
            "        \":jar\",",
            "        \"@com_google_code_findbugs_jsr305\",",
            "        \"@javax_inject\",",
            "    ],",
            ")",
        ]),
    )

def com_google_auto_service():
    java_import_external(
        name = "com_google_auto_service",
        jar_sha256 = "e422d49c312fd2031222e7306e8108c1b4118eb9c049f1b51eca280bed87e924",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/auto/service/auto-service/1.0-rc4/auto-service-1.0-rc4.jar",
            "http://repo1.maven.org/maven2/com/google/auto/service/auto-service/1.0-rc4/auto-service-1.0-rc4.jar",
        ],
        licenses = ["notice"],  # Apache 2.0
        neverlink = True,
        generated_rule_name = "compile",
        generated_linkable_rule_name = "processor",
        deps = [
            "@com_google_auto_common",
            "@com_google_guava",
        ],
        extra_build_file_content = "\n".join([
            "java_plugin(",
            "    name = \"AutoServiceProcessor\",",
            "    output_licenses = [\"unencumbered\"],",
            "    processor_class = \"com.google.auto.service.processor.AutoServiceProcessor\",",
            "    deps = [\":processor\"],",
            ")",
            "",
            "java_library(",
            "    name = \"com_google_auto_service\",",
            "    exported_plugins = [\":AutoServiceProcessor\"],",
            "    exports = [\":compile\"],",
            ")",
        ]),
    )

def com_google_auto_value():
    java_import_external(
        name = "com_google_auto_value",
        jar_sha256 = "edbe65a5c53e3d4f5cb10b055d4884ae7705a7cd697be4b2a5d8427761b8ba12",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/auto/value/auto-value/1.6.2/auto-value-1.6.2.jar",
            "http://repo1.maven.org/maven2/com/google/auto/value/auto-value/1.6.2/auto-value-1.6.2.jar",
        ],
        licenses = ["notice"],  # Apache 2.0
        neverlink = True,
        generated_rule_name = "compile",
        generated_linkable_rule_name = "processor",
        deps = [
            "@com_google_auto_common",
            "@com_google_code_findbugs_jsr305",
            "@com_google_guava",
        ],
        extra_build_file_content = "\n".join([
            "java_plugin(",
            "    name = \"AutoAnnotationProcessor\",",
            "    output_licenses = [\"unencumbered\"],",
            "    processor_class = \"com.google.auto.value.processor.AutoAnnotationProcessor\",",
            "    tags = [\"annotation=com.google.auto.value.AutoAnnotation;genclass=${package}.AutoAnnotation_${outerclasses}${classname}_${methodname}\"],",
            "    deps = [\":processor\"],",
            ")",
            "",
            "java_plugin(",
            "    name = \"AutoValueProcessor\",",
            "    output_licenses = [\"unencumbered\"],",
            "    processor_class = \"com.google.auto.value.processor.AutoValueProcessor\",",
            "    tags = [\"annotation=com.google.auto.value.AutoValue;genclass=${package}.AutoValue_${outerclasses}${classname}\"],",
            "    deps = [\":processor\"],",
            ")",
            "",
            "java_library(",
            "    name = \"com_google_auto_value\",",
            "    exported_plugins = [",
            "        \":AutoAnnotationProcessor\",",
            "        \":AutoValueProcessor\",",
            "    ],",
            "    exports = [",
            "        \":compile\",",
            "        \"@com_google_code_findbugs_jsr305\",",
            "    ],",
            ")",
        ]),
    )

def com_google_gson():
    java_import_external(
        name = "com_google_gson",
        licenses = ["notice"],  # Apache 2.0
        jar_sha256 = "233a0149fc365c9f6edbd683cfe266b19bdc773be98eabdaf6b3c924b48e7d81",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/code/gson/gson/2.8.5/gson-2.8.5.jar",
            "http://maven.ibiblio.org/maven2/com/google/code/gson/gson/2.8.5/gson-2.8.5.jar",
        ],
    )

def com_google_cloud_bigdataoss_gcsio():
    java_import_external(
        name = "com_google_cloud_bigdataoss_gcsio",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "8ef468b82542ae8d0428b0c60eb6009bf05f2b80e34715ee6bb27c5def66b5be",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/cloud/bigdataoss/gcsio/1.4.5/gcsio-1.4.5.jar",
            "http://repo1.maven.org/maven2/com/google/cloud/bigdataoss/gcsio/1.4.5/gcsio-1.4.5.jar",
        ],
        deps = [
            "@com_google_api_client_java6",
            "@com_google_api_client_jackson2",
            "@com_google_apis_google_api_services_storage",
            "@com_google_code_findbugs_jsr305",
            "@com_google_guava",
            "@com_google_oauth_client",
            "@com_google_oauth_client_java6",
            #"@junit",
            "@org_slf4j_api",
            "@com_google_cloud_bigdataoss_util",
        ],
    )

def com_google_cloud_bigdataoss_util():
    java_import_external(
        name = "com_google_cloud_bigdataoss_util",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "56c93d9cf72a2725c45c6d26530c7065900a7c962ec8f7c0edc5f4164652753d",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/cloud/bigdataoss/util/1.4.5/util-1.4.5.jar",
            "http://maven.ibiblio.org/maven2/com/google/cloud/bigdataoss/util/1.4.5/util-1.4.5.jar",
        ],
        deps = [
            "@com_google_api_client_java6",
            "@com_google_api_client_jackson2",
            "@com_google_apis_google_api_services_storage",
            "@com_google_code_findbugs_jsr305",
            "@com_google_guava",
            "@com_google_oauth_client",
            "@com_google_oauth_client_java6",
            #"@junit",
            "@org_slf4j_api",
        ],
    )

def com_google_code_findbugs_jsr305():
    java_import_external(
        name = "com_google_code_findbugs_jsr305",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar",
            "http://maven.ibiblio.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar",
        ],
    )

def com_google_dagger():
    java_import_external(
        name = "com_google_dagger",
        jar_sha256 = "1f14720ffc3152a4207e374edb2ce114d94625058a6ef48a35cb67764dac4756",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/dagger/dagger/2.15/dagger-2.15.jar",
            "http://maven.ibiblio.org/maven2/com/google/dagger/dagger/2.15/dagger-2.15.jar",
        ],
        licenses = ["notice"],  # Apache 2.0
        deps = ["@javax_inject"],
        generated_rule_name = "runtime",
        extra_build_file_content = "\n".join([
            "java_library(",
            "    name = \"com_google_dagger\",",
            "    exported_plugins = [\"@com_google_dagger_compiler//:ComponentProcessor\"],",
            "    exports = [",
            "        \":runtime\",",
            "        \"@javax_inject\",",
            "    ],",
            ")",
        ]),
    )

def com_google_dagger_compiler():
    java_import_external(
        name = "com_google_dagger_compiler",
        jar_sha256 = "ecd1e8ee0224312ae29203767fb8ec70af13e4f2724df2174ba0d2867cd2de78",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/dagger/dagger-compiler/2.15/dagger-compiler-2.15.jar",
            "http://repo1.maven.org/maven2/com/google/dagger/dagger-compiler/2.15/dagger-compiler-2.15.jar",
        ],
        licenses = ["notice"],  # Apache 2.0
        deps = [
            "@com_google_dagger//:runtime",
            "@com_google_dagger_producers//:runtime",
            "@com_google_code_findbugs_jsr305",
            "@com_google_googlejavaformat_google_java_format",
            "@com_google_guava",
            "@com_squareup_javapoet",
            "@javax_annotation_jsr250_api",
            "@javax_inject",
        ],
        extra_build_file_content = "\n".join([
            "java_plugin(",
            "    name = \"ComponentProcessor\",",
            "    output_licenses = [\"unencumbered\"],",
            "    processor_class = \"dagger.internal.codegen.ComponentProcessor\",",
            "    generates_api = 1,",
            "    tags = [",
            "        \"annotation=dagger.Component;genclass=${package}.Dagger${outerclasses}${classname}\",",
            "        \"annotation=dagger.producers.ProductionComponent;genclass=${package}.Dagger${outerclasses}${classname}\",",
            "    ],",
            "    deps = [\":com_google_dagger_compiler\"],",
            ")",
        ]),
    )

def com_google_dagger_producers():
    java_import_external(
        name = "com_google_dagger_producers",
        jar_sha256 = "eb189206f80df260de4331bb51e92a94e06f5cbf5ef3d1492d34c5e139e92eb1",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/dagger/dagger-producers/2.15/dagger-producers-2.15.jar",
            "http://maven.ibiblio.org/maven2/com/google/dagger/dagger-producers/2.15/dagger-producers-2.15.jar",
        ],
        licenses = ["notice"],  # Apache 2.0
        deps = [
            "@com_google_dagger//:runtime",
            "@com_google_code_findbugs_jsr305",
            "@com_google_guava",
            "@javax_inject",
        ],
        generated_rule_name = "runtime",
        extra_build_file_content = "\n".join([
            "java_library(",
            "    name = \"com_google_dagger\",",
            "    exported_plugins = [\"@com_google_dagger_compiler//:ComponentProcessor\"],",
            "    exports = [",
            "        \":runtime\",",
            "        \"@javax_inject\",",
            "    ],",
            ")",
        ]),
    )

def com_google_errorprone_error_prone_annotations():
    java_import_external(
        name = "com_google_errorprone_error_prone_annotations",
        jar_sha256 = "03d0329547c13da9e17c634d1049ea2ead093925e290567e1a364fd6b1fc7ff8",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/errorprone/error_prone_annotations/2.1.3/error_prone_annotations-2.1.3.jar",
            "http://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.1.3/error_prone_annotations-2.1.3.jar",
        ],
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_errorprone_javac_shaded():
    java_import_external(
        name = "com_google_errorprone_javac_shaded",
        # GNU General Public License, version 2, with the Classpath Exception
        # http://openjdk.java.net/legal/gplv2+ce.html
        licenses = ["TODO"],
        jar_sha256 = "65bfccf60986c47fbc17c9ebab0be626afc41741e0a6ec7109e0768817a36f30",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/errorprone/javac-shaded/9-dev-r4023-3/javac-shaded-9-dev-r4023-3.jar",
            "http://maven.ibiblio.org/maven2/com/google/errorprone/javac-shaded/9-dev-r4023-3/javac-shaded-9-dev-r4023-3.jar",
        ],
    )

def com_google_flogger():
    java_import_external(
        name = "com_google_flogger",
        licenses = ["notice"],  # Apache 2.0
        jar_sha256 = "82f6d7fcafe92adabbb3e0ccc82c2b3a40c5c72343fe2a3226b34f8fa0f61bcb",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/flogger/flogger/0.1/flogger-0.1.jar",
            "http://maven.ibiblio.org/maven2/com/google/flogger/flogger/0.1/flogger-0.1.jar",
        ],
        deps = ["@com_google_code_findbugs_jsr305"],
    )

def com_google_flogger_system_backend():
    java_import_external(
        name = "com_google_flogger_system_backend",
        licenses = ["notice"],  # Apache 2.0
        jar_sha256 = "7b5c1816fb174a768e7a6a09800feb53a6a094af7cbc5d3a9663b2735e97074d",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/flogger/flogger-system-backend/0.1/flogger-system-backend-0.1.jar",
            "http://maven.ibiblio.org/maven2/com/google/flogger/flogger-system-backend/0.1/flogger-system-backend-0.1.jar",
        ],
        deps = [
            "@com_google_flogger",
            "@com_google_code_findbugs_jsr305",
        ],
    )

def com_google_gdata_core():
    java_import_external(
        name = "com_google_gdata_core",
        jar_sha256 = "671fb963dd0bc767a69c7e4a74c07cf8dad3912bd40d37e600cc2b06d7a42dea",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/gdata/core/1.47.1/core-1.47.1.jar",
            "http://repo1.maven.org/maven2/com/google/gdata/core/1.47.1/core-1.47.1.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@com_google_guava",
            "@com_google_oauth_client_jetty",
            "@com_google_code_findbugs_jsr305",
            "@javax_mail",
        ],
    )

def com_google_googlejavaformat_google_java_format():
    java_import_external(
        name = "com_google_googlejavaformat_google_java_format",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "39d18ec9ab610097074bf49e971285488eaf5d0bc2369df0a0d5a3f9f9de2faa",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/googlejavaformat/google-java-format/1.4/google-java-format-1.4.jar",
            "http://repo1.maven.org/maven2/com/google/googlejavaformat/google-java-format/1.4/google-java-format-1.4.jar",
        ],
        deps = [
            "@com_google_guava",
            "@com_google_errorprone_javac_shaded",
        ],
    )

def com_google_guava():
    java_import_external(
        name = "com_google_guava",
        jar_sha256 = "4a5aa70cc968a4d137e599ad37553e5cfeed2265e8c193476d7119036c536fe7",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/guava/guava/27.1-jre/guava-27.1-jre.jar",
            "http://maven.ibiblio.org/maven2/com/google/guava/guava/27.1-jre/guava-27.1-jre.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        exports = [
            "@com_google_code_findbugs_jsr305",
            "@com_google_errorprone_error_prone_annotations",
            "@com_google_guava_failureaccess",
            "@com_google_guava_listenablefuture",
        ],
    )

def com_google_guava_failureaccess():
    java_import_external(
        name = "com_google_guava_failureaccess",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "a171ee4c734dd2da837e4b16be9df4661afab72a41adaf31eb84dfdaf936ca26",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar",
            "http://maven.ibiblio.org/maven2/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar",
        ],
    )

def com_google_guava_listenablefuture():
    java_import_external(
        name = "com_google_guava_listenablefuture",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar",
            "http://maven.ibiblio.org/maven2/com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar",
        ],
    )

def com_google_guava_testlib():
    java_import_external(
        name = "com_google_guava_testlib",
        jar_sha256 = "777159901c2a859497fc24b392bbfd9ad3c527d435a735d20aa159c6cf487553",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/guava/guava-testlib/25.0-jre/guava-testlib-25.0-jre.jar",
            "http://maven.ibiblio.org/maven2/com/google/guava/guava/25.0-jre/guava-testlib-25.0-jre.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        testonly_ = True,
        deps = [
            "@com_google_code_findbugs_jsr305",
            "@com_google_errorprone_error_prone_annotations",
            "@com_google_guava",
            "@junit",
        ],
    )

def com_google_gwt_user():
    java_import_external(
        name = "com_google_gwt_user",
        neverlink = 1,
        licenses = ["notice"],  # GWT Terms
        jar_sha256 = "9f420f0d0c2f177d71cb1794b3be1418f9755f6e4181101af3951b8302b9556d",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/gwt/gwt-user/2.8.2/gwt-user-2.8.2.jar",
            "http://repo1.maven.org/maven2/com/google/gwt/gwt-user/2.8.2/gwt-user-2.8.2.jar",
        ],
        deps = [
            "@javax_validation_api",
            "@javax_servlet_api",
            "@org_w3c_css_sac",
        ],
    )

def com_google_http_client():
    java_import_external(
        name = "com_google_http_client",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "fb7d80a515da4618e2b402e1fef96999e07621b381a5889ef091482c5a3e961d",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/http-client/google-http-client/1.25.0/google-http-client-1.25.0.jar",
            "http://maven.ibiblio.org/maven2/com/google/http-client/google-http-client/1.25.0/google-http-client-1.25.0.jar",
        ],
        deps = [
            "@com_google_code_findbugs_jsr305",
            "@com_google_guava",
            "@org_apache_httpcomponents_httpclient",
            "@commons_codec",
        ],
    )

def com_google_http_client_appengine():
    java_import_external(
        name = "com_google_http_client_appengine",
        jar_sha256 = "44a92b5caf023cc526fdbc94d5259457988a9a557a5ce570cbfbafad0fd32420",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/http-client/google-http-client-appengine/1.22.0/google-http-client-appengine-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/http-client/google-http-client-appengine/1.22.0/google-http-client-appengine-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@com_google_http_client",
            "@com_google_appengine_api_1_0_sdk",
        ],
    )

def com_google_http_client_jackson2():
    java_import_external(
        name = "com_google_http_client_jackson2",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "f9e7e0d318860a2092d70b56331976280c4e9348a065ede3b99c92aa032fd853",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/http-client/google-http-client-jackson2/1.25.0/google-http-client-jackson2-1.25.0.jar",
            "http://repo1.maven.org/maven2/com/google/http-client/google-http-client-jackson2/1.25.0/google-http-client-jackson2-1.25.0.jar",
        ],
        deps = [
            "@com_google_http_client",
            "@com_fasterxml_jackson_core",
        ],
    )

def com_google_oauth_client():
    java_import_external(
        name = "com_google_oauth_client",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "7e2929133d4231e702b5956a7e5dc8347a352acc1e97082b40c3585b81cd3501",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/oauth-client/google-oauth-client/1.25.0/google-oauth-client-1.25.0.jar",
            "http://repo1.maven.org/maven2/com/google/oauth-client/google-oauth-client/1.25.0/google-oauth-client-1.25.0.jar",
        ],
        deps = [
            "@com_google_http_client",
            "@com_google_code_findbugs_jsr305",
        ],
    )

def com_google_oauth_client_appengine():
    java_import_external(
        name = "com_google_oauth_client_appengine",
        jar_sha256 = "9d78ad610143fffea773e5fb9214fa1a6889c6d08be627d0b45e189f613f7877",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/oauth-client/google-oauth-client-appengine/1.22.0/google-oauth-client-appengine-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/oauth-client/google-oauth-client-appengine/1.22.0/google-oauth-client-appengine-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@com_google_http_client_appengine",
            "@com_google_oauth_client",
            "@com_google_oauth_client_servlet",
            "@com_google_appengine_api_1_0_sdk",
            "@javax_servlet_api",
        ],
    )

def com_google_oauth_client_java6():
    java_import_external(
        name = "com_google_oauth_client_java6",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "1065d7ec93a9ca93005e85d73f23f71353dd731f5c5f0310d66735ad81a16c33",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/oauth-client/google-oauth-client-java6/1.27.0/google-oauth-client-java6-1.27.0.jar",
            "http://repo1.maven.org/maven2/com/google/oauth-client/google-oauth-client-java6/1.27.0/google-oauth-client-java6-1.27.0.jar",
        ],
        deps = ["@com_google_oauth_client"],
    )

def com_google_oauth_client_jetty():
    java_import_external(
        name = "com_google_oauth_client_jetty",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "fdeebd0a97b265af7649b77e7e5e22937a7ea99148440647ba75f73c5eacddd2",
        jar_urls = [
            "https://maven.ibiblio.org/maven2/com/google/oauth-client/google-oauth-client-jetty/1.22.0/google-oauth-client-jetty-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/oauth-client/google-oauth-client-jetty/1.22.0/google-oauth-client-jetty-1.22.0.jar",
        ],
        deps = [
            "@com_google_oauth_client_java6",
            "@org_mortbay_jetty",
        ],
    )

def com_google_oauth_client_servlet():
    java_import_external(
        name = "com_google_oauth_client_servlet",
        jar_sha256 = "6956ac1bd055ebf0b1592a005f029a551af3039584a32fe03854f0d2f6f022ef",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/oauth-client/google-oauth-client-servlet/1.22.0/google-oauth-client-servlet-1.22.0.jar",
            "http://repo1.maven.org/maven2/com/google/oauth-client/google-oauth-client-servlet/1.22.0/google-oauth-client-servlet-1.22.0.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        deps = [
            "@com_google_oauth_client",
            "@javax_servlet_api",
        ],
    )

def com_google_protobuf():
    http_archive(
        name = "com_google_protobuf",
        strip_prefix = "protobuf-3.6.1.3",
        sha256 = "73fdad358857e120fd0fa19e071a96e15c0f23bb25f85d3f7009abfd4f264a2a",
        urls = [
            "https://mirror.bazel.build/github.com/google/protobuf/archive/v3.6.1.3.tar.gz",
            "https://github.com/protocolbuffers/protobuf/archive/v3.6.1.3.tar.gz",
        ],
    )

def com_google_protobuf_java():
    java_import_external(
        name = "com_google_protobuf_java",
        jar_sha256 = "5636b013420f19c0a5342dab6de33956e20a40b06681d2cf021266d6ef478c6e",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/protobuf/protobuf-java/2.6.0/protobuf-java-2.6.0.jar",
            "http://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/2.6.0/protobuf-java-2.6.0.jar",
        ],
        # New BSD license
        # http://www.opensource.org/licenses/bsd-license.php
        # The Apache Software License, Version 2.0
        # http://www.apache.org/licenses/LICENSE-2.0.txt
        licenses = ["notice"],
    )

def com_google_re2j():
    java_import_external(
        name = "com_google_re2j",
        jar_sha256 = "24ada84d1b5de584e3e84b06f0c7dd562cee6eafe8dea8083bd8eb123823bbe7",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/re2j/re2j/1.1/re2j-1.1.jar",
            "http://repo1.maven.org/maven2/com/google/re2j/re2j/1.1/re2j-1.1.jar",
        ],
        licenses = ["notice"],  # The Go license
    )

def com_google_template_soy():
    java_import_external(
        name = "com_google_template_soy",
        jar_sha256 = "3c4e61234e9ee9f79411da997e23b201bcf281255469c76d162dac07a67dbb78",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/google/template/soy/2017-06-22/soy-2017-06-22.jar",
            "http://central.maven.org/maven2/com/google/template/soy/2017-06-22/soy-2017-06-22.jar",
        ],
        deps = [
            "@args4j",
            "@org_ow2_asm",
            "@org_ow2_asm_analysis",
            "@org_ow2_asm_commons",
            "@org_ow2_asm_util",
            "@com_google_guava",
            "@com_google_inject_guice",
            "@com_google_inject_extensions_guice_assistedinject",
            "@com_google_inject_extensions_guice_multibindings",
            "@com_ibm_icu_icu4j",
            "@org_json",
            "@com_google_code_findbugs_jsr305",
            "@javax_inject",
            "@com_google_common_html_types",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        extra_build_file_content = "\n".join([
            ("java_binary(\n" +
             "    name = \"%s\",\n" +
             "    main_class = \"com.google.template.soy.%s\",\n" +
             "    output_licenses = [\"unencumbered\"],\n" +
             "    runtime_deps = [\":com_google_template_soy\"],\n" +
             ")\n") % (name, name)
            for name in (
                "SoyParseInfoGenerator",
                "SoyToJbcSrcCompiler",
                "SoyToJsSrcCompiler",
                "SoyToPySrcCompiler",
                "SoyToIncrementalDomSrcCompiler",
            )
        ]),
    )

def com_google_truth():
    java_import_external(
        name = "com_google_truth",
        jar_sha256 = "0f7dced2a16e55a77e44fc3ff9c5be98d4bf4bb30abc18d78ffd735df950a69f",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/truth/truth/0.45/truth-0.45.jar",
            "http://repo1.maven.org/maven2/com/google/truth/truth/0.45/truth-0.45.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        testonly_ = True,
        deps = [
            "@com_google_guava",
            "@junit",
            "@com_google_auto_value",
            "@com_google_errorprone_error_prone_annotations",
            "@com_googlecode_java_diff_utils_diffutils",
        ],
    )

def com_google_truth_extensions_truth_java8_extension():
    java_import_external(
        name = "com_google_truth_extensions_truth_java8_extension",
        jar_sha256 = "dc1fedf6c13b1b1a4c4fa3e810f11df070ea4701765f05176f8bdcb5520c7de4",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/google/truth/extensions/truth-java8-extension/0.45/truth-java8-extension-0.45.jar",
            "http://central.maven.org/maven2/com/google/truth/extensions/truth-java8-extension/0.45/truth-java8-extension-0.45.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        testonly_ = True,
        deps = [
            "@com_google_truth",
            "@com_google_errorprone_error_prone_annotations",
        ],
    )

def com_googlecode_charts4j():
    java_import_external(
        name = "com_googlecode_charts4j",
        jar_sha256 = "6ac5ed6a390a585fecaed95d3ce6b96a8cfe95adb1e76bd93376e7e37249020a",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/googlecode/charts4j/charts4j/1.3/charts4j-1.3.jar",
            "http://repo1.maven.org/maven2/com/googlecode/charts4j/charts4j/1.3/charts4j-1.3.jar",
        ],
        licenses = ["notice"],  # The MIT License
    )

def com_googlecode_java_diff_utils_diffutils():
    java_import_external(
        name = "com_googlecode_java_diff_utils_diffutils",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "61ba4dc49adca95243beaa0569adc2a23aedb5292ae78aa01186fa782ebdc5c2",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/googlecode/java-diff-utils/diffutils/1.3.0/diffutils-1.3.0.jar",
            "http://repo1.maven.org/maven2/com/googlecode/java-diff-utils/diffutils/1.3.0/diffutils-1.3.0.jar",
        ],
    )

def com_googlecode_json_simple():
    java_import_external(
        name = "com_googlecode_json_simple",
        jar_sha256 = "4e69696892b88b41c55d49ab2fdcc21eead92bf54acc588c0050596c3b75199c",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/googlecode/json-simple/json-simple/1.1.1/json-simple-1.1.1.jar",
            "http://repo1.maven.org/maven2/com/googlecode/json-simple/json-simple/1.1.1/json-simple-1.1.1.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
    )

def com_ibm_icu_icu4j():
    java_import_external(
        name = "com_ibm_icu_icu4j",
        jar_sha256 = "759d89ed2f8c6a6b627ab954be5913fbdc464f62254a513294e52260f28591ee",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/ibm/icu/icu4j/57.1/icu4j-57.1.jar",
            "http://repo1.maven.org/maven2/com/ibm/icu/icu4j/57.1/icu4j-57.1.jar",
        ],
        licenses = ["notice"],  # ICU License
    )

def com_jcraft_jzlib():
    java_import_external(
        name = "com_jcraft_jzlib",
        jar_sha256 = "89b1360f407381bf61fde411019d8cbd009ebb10cff715f3669017a031027560",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar",
            "http://repo1.maven.org/maven2/com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar",
        ],
        licenses = ["notice"],  # BSD
    )

def com_squareup_javapoet():
    java_import_external(
        name = "com_squareup_javapoet",
        licenses = ["notice"],  # Apache 2.0
        jar_sha256 = "8e108c92027bb428196f10fa11cffbe589f7648a6af2016d652279385fdfd789",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/squareup/javapoet/1.8.0/javapoet-1.8.0.jar",
            "http://repo1.maven.org/maven2/com/squareup/javapoet/1.8.0/javapoet-1.8.0.jar",
        ],
    )

def com_squareup_javawriter():
    java_import_external(
        name = "com_squareup_javawriter",
        jar_sha256 = "39b054910ff212d4379129a89070fb7dbb1f341371c925e9e99904f154a22d93",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/squareup/javawriter/2.5.1/javawriter-2.5.1.jar",
            "http://repo1.maven.org/maven2/com/squareup/javawriter/2.5.1/javawriter-2.5.1.jar",
        ],
        licenses = ["notice"],  # Apache 2.0
    )

def com_sun_xml_bind_jaxb_core():
    java_import_external(
        name = "com_sun_xml_bind_jaxb_core",
        jar_sha256 = "33061af8d2a07379d452f8f0fddcbbf518428dfd3b83d9e2d479c1948020795b",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/sun/xml/bind/jaxb-core/2.3.0/jaxb-core-2.3.0.jar",
            "http://repo1.maven.org/maven2/com/sun/xml/bind/jaxb-core/2.3.0/jaxb-core-2.3.0.jar",
        ],
        licenses = ["reciprocal"],  # CDDL 1.1 or GPLv2 (We choo-choo-choose the CDDL)
    )

def com_sun_xml_bind_jaxb_impl():
    java_import_external(
        name = "com_sun_xml_bind_jaxb_impl",
        jar_sha256 = "edd691fc6ad7c7f3167e1a6833372367bedf6f4f2a4b8230df444a59bb3718b1",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/sun/xml/bind/jaxb-impl/2.3.0/jaxb-impl-2.3.0.jar",
            "http://repo1.maven.org/maven2/com/sun/xml/bind/jaxb-impl/2.3.0/jaxb-impl-2.3.0.jar",
        ],
        licenses = ["reciprocal"],  # CDDL 1.1 or GPLv2 (We choo-choo-choose the CDDL)
    )

def com_sun_xml_bind_jaxb_jxc():
    java_import_external(
        name = "com_sun_xml_bind_jaxb_jxc",
        licenses = ["restricted"],  # CDDL+GPL License
        jar_sha256 = "97bfe84b4862718ef86245f32d9f18bc84c03b442edfbe9070fcfe3ea2ca9dc5",
        jar_urls = [
            "http://repo1.maven.org/maven2/com/sun/xml/bind/jaxb-jxc/2.3.0/jaxb-jxc-2.3.0.jar",
            "http://maven.ibiblio.org/maven2/com/sun/xml/bind/jaxb-jxc/2.3.0/jaxb-jxc-2.3.0.jar",
        ],
    )

def com_sun_xml_bind_jaxb_xjc():
    java_import_external(
        name = "com_sun_xml_bind_jaxb_xjc",
        jar_sha256 = "01c0cb72ac0e5471f4aa979ec51a0587accdd3cfd2012fa52184caf844589c98",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/sun/xml/bind/jaxb-xjc/2.3.0/jaxb-xjc-2.3.0.jar",
            "http://repo1.maven.org/maven2/com/sun/xml/bind/jaxb-xjc/2.3.0/jaxb-xjc-2.3.0.jar",
        ],
        licenses = ["reciprocal"],  # CDDL 1.1 or GPLv2 (We choo-choo-choose the CDDL)
        extra_build_file_content = "\n".join([
            "java_binary(",
            "    name = \"XJCFacade\",",
            "    main_class = \"com.sun.tools.xjc.XJCFacade\",",
            "    runtime_deps = [",
            "        \":com_sun_xml_bind_jaxb_xjc\",",
            "        \"@javax_xml_bind_jaxb_api\",",
            "        \"@com_sun_xml_bind_jaxb_core\",",
            "        \"@com_sun_xml_bind_jaxb_impl\",",
            "    ],",
            ")",
        ]),
    )

def com_thoughtworks_paranamer():
    java_import_external(
        name = "com_thoughtworks_paranamer",
        jar_sha256 = "63e3f53f8f70784b65c25b2ee475813979d6d0e7f7b2510b364c4e1f4a803ccc",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/thoughtworks/paranamer/paranamer/2.7/paranamer-2.7.jar",
            "http://repo1.maven.org/maven2/com/thoughtworks/paranamer/paranamer/2.7/paranamer-2.7.jar",
            "http://maven.ibiblio.org/maven2/com/thoughtworks/paranamer/paranamer/2.7/paranamer-2.7.jar",
        ],
        licenses = ["notice"],  # BSD
    )

def commons_codec():
    java_import_external(
        name = "commons_codec",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "4241dfa94e711d435f29a4604a3e2de5c4aa3c165e23bd066be6fc1fc4309569",
        jar_urls = [
            "http://repo1.maven.org/maven2/commons-codec/commons-codec/1.10/commons-codec-1.10.jar",
            "http://maven.ibiblio.org/maven2/commons-codec/commons-codec/1.10/commons-codec-1.10.jar",
        ],
    )

def commons_logging():
    java_import_external(
        name = "commons_logging",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "daddea1ea0be0f56978ab3006b8ac92834afeefbd9b7e4e6316fca57df0fa636",
        jar_urls = [
            "http://repo1.maven.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.jar",
            "http://maven.ibiblio.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.jar",
        ],
    )

def dnsjava():
    java_import_external(
        name = "dnsjava",
        jar_sha256 = "2c52a6fabd5af9331d73fc7787dafc32a56bd8019c49f89749c2eeef244e303c",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/dnsjava/dnsjava/2.1.7/dnsjava-2.1.7.jar",
            "http://repo1.maven.org/maven2/dnsjava/dnsjava/2.1.7/dnsjava-2.1.7.jar",
        ],
        licenses = ["notice"],  # BSD 2-Clause license
    )

def io_netty_buffer():
    java_import_external(
        name = "io_netty_buffer",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "ca21c19fad249b27d762f0ad1685c52a8161a744c7cc3f7fa7400ef072c94dbf",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/io/netty/netty-buffer/4.1.28.Final/netty-buffer-4.1.28.Final.jar",
            "http://repo1.maven.org/maven2/io/netty/netty-buffer/4.1.28.Final/netty-buffer-4.1.28.Final.jar",
        ],
        deps = ["@io_netty_common"],
    )

def io_netty_codec():
    java_import_external(
        name = "io_netty_codec",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "32590789123f462aee92b3b6ddef41cbb134a8664902dcf8db563f6e4c0f1447",
        jar_urls = [
            "http://repo1.maven.org/maven2/io/netty/netty-codec/4.1.28.Final/netty-codec-4.1.28.Final.jar",
            "http://maven.ibiblio.org/maven2/io/netty/netty-codec/4.1.28.Final/netty-codec-4.1.28.Final.jar",
        ],
        deps = ["@io_netty_transport"],
    )

def io_netty_codec_http():
    java_import_external(
        name = "io_netty_codec_http",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "6d8838da021a36f10f9497be6d5eca575d6eae77e463841bd893b62a7b589006",
        jar_urls = [
            "http://repo1.maven.org/maven2/io/netty/netty-codec-http/4.1.28.Final/netty-codec-http-4.1.28.Final.jar",
            "http://maven.ibiblio.org/maven2/io/netty/netty-codec-http/4.1.28.Final/netty-codec-http-4.1.28.Final.jar",
        ],
        deps = ["@io_netty_codec"],
    )

def io_netty_common():
    java_import_external(
        name = "io_netty_common",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "f81527b7db95e6bc4577e3633af34b61541d3b34408032079f1431a3358f0a93",
        jar_urls = [
            "http://repo1.maven.org/maven2/io/netty/netty-common/4.1.28.Final/netty-common-4.1.28.Final.jar",
            "http://maven.ibiblio.org/maven2/io/netty/netty-common/4.1.28.Final/netty-common-4.1.28.Final.jar",
        ],
    )

def io_netty_handler():
    java_import_external(
        name = "io_netty_handler",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "c0b2ef68958ae0c2cc2534aed47f060bab8eee816a2d81b20af38d07d5292d83",
        jar_urls = [
            "http://repo1.maven.org/maven2/io/netty/netty-handler/4.1.28.Final/netty-handler-4.1.28.Final.jar",
            "http://maven.ibiblio.org/maven2/io/netty/netty-handler/4.1.28.Final/netty-handler-4.1.28.Final.jar",
        ],
    )

def io_netty_resolver():
    java_import_external(
        name = "io_netty_resolver",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "19a6442a27d07423e8b1ce306670724192d828863e2ed840e8c45c92e80bbf2d",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/io/netty/netty-resolver/4.1.28.Final/netty-resolver-4.1.28.Final.jar",
            "http://repo1.maven.org/maven2/io/netty/netty-resolver/4.1.28.Final/netty-resolver-4.1.28.Final.jar",
        ],
        deps = ["@io_netty_common"],
    )

def io_netty_tcnative():
    java_import_external(
        name = "io_netty_tcnative",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "8a925a983af49f08abcea13aa76752246970d678fb8596afd548a84b2060c32b",
        jar_urls = [
            "http://repo1.maven.org/maven2/io/netty/netty-tcnative/2.0.12.Final/netty-tcnative-2.0.12.Final.jar",
            "http://maven.ibiblio.org/maven2/io/netty/netty-tcnative/2.0.12.Final/netty-tcnative-2.0.12.Final.jar",
        ],
    )

def io_netty_tcnative_boringssl_static():
    java_import_external(
        name = "io_netty_tcnative_boringssl_static",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "3df756e569504137e90ff368c2fe09f1f953efeddb717d47ed391dfa6ba8b7e3",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/io/netty/netty-tcnative-boringssl-static/2.0.12.Final/netty-tcnative-boringssl-static-2.0.12.Final.jar",
            "http://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/2.0.12.Final/netty-tcnative-boringssl-static-2.0.12.Final.jar",
        ],
    )

def io_netty_transport():
    java_import_external(
        name = "io_netty_transport",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "85fd10967f9645e53a3eee4474ee9a44d3085863db66e947828970b0cf5be122",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/io/netty/netty-transport/4.1.28.Final/netty-transport-4.1.28.Final.jar",
            "http://repo1.maven.org/maven2/io/netty/netty-transport/4.1.28.Final/netty-transport-4.1.28.Final.jar",
        ],
        deps = [
            "@io_netty_buffer",
            "@io_netty_resolver",
        ],
    )

def it_unimi_dsi_fastutil():
    java_import_external(
        name = "it_unimi_dsi_fastutil",
        jar_sha256 = "af5a1ad8261d0607e7d8d3759d97ba7ad834a6be8277466aaccf2121a75963c7",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/it/unimi/dsi/fastutil/6.5.16/fastutil-6.5.16.jar",
            "http://repo1.maven.org/maven2/it/unimi/dsi/fastutil/6.5.16/fastutil-6.5.16.jar",
        ],
        licenses = ["notice"],  # Apache License, Version 2.0
    )

def com_sun_activation_javax_activation():
    java_import_external(
        name = "com_sun_activation_javax_activation",
        licenses = ["restricted"],  # CDDL/GPLv2+CE
        jar_sha256 = "993302b16cd7056f21e779cc577d175a810bb4900ef73cd8fbf2b50f928ba9ce",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/com/sun/activation/javax.activation/1.2.0/javax.activation-1.2.0.jar",
            "http://repo1.maven.org/maven2/com/sun/activation/javax.activation/1.2.0/javax.activation-1.2.0.jar",
        ],
    )

def javax_annotation_jsr250_api():
    java_import_external(
        name = "javax_annotation_jsr250_api",
        licenses = ["reciprocal"],  # COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0
        jar_sha256 = "a1a922d0d9b6d183ed3800dfac01d1e1eb159f0e8c6f94736931c1def54a941f",
        jar_urls = [
            "http://repo1.maven.org/maven2/javax/annotation/jsr250-api/1.0/jsr250-api-1.0.jar",
            "http://maven.ibiblio.org/maven2/javax/annotation/jsr250-api/1.0/jsr250-api-1.0.jar",
        ],
    )

def javax_inject():
    java_import_external(
        name = "javax_inject",
        jar_sha256 = "91c77044a50c481636c32d916fd89c9118a72195390452c81065080f957de7ff",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar",
            "http://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar",
        ],
        licenses = ["notice"],  # The Apache Software License, Version 2.0
    )

def javax_mail():
    java_import_external(
        name = "javax_mail",
        jar_sha256 = "96868f82264ebd9b7d41f04d78cbe87ab75d68a7bbf8edfb82416aabe9b54b6c",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/javax/mail/mail/1.4/mail-1.4.jar",
            "http://repo1.maven.org/maven2/javax/mail/mail/1.4/mail-1.4.jar",
        ],
        licenses = ["reciprocal"],  # Common Development and Distribution License (CDDL) v1.0
        deps = ["@com_sun_activation_javax_activation"],
    )

def javax_servlet_api():
    java_import_external(
        name = "javax_servlet_api",
        jar_sha256 = "c658ea360a70faeeadb66fb3c90a702e4142a0ab7768f9ae9828678e0d9ad4dc",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/javax/servlet/servlet-api/2.5/servlet-api-2.5.jar",
            "http://repo1.maven.org/maven2/javax/servlet/servlet-api/2.5/servlet-api-2.5.jar",
        ],
        licenses = ["notice"],  # Apache
    )

def javax_validation_api():
    java_import_external(
        name = "javax_validation_api",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "e459f313ebc6db2483f8ceaad39af07086361b474fa92e40f442e8de5d9895dc",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/javax/validation/validation-api/1.0.0.GA/validation-api-1.0.0.GA.jar",
            "http://repo1.maven.org/maven2/javax/validation/validation-api/1.0.0.GA/validation-api-1.0.0.GA.jar",
        ],
    )

def javax_xml_bind_jaxb_api():
    java_import_external(
        name = "javax_xml_bind_jaxb_api",
        jar_sha256 = "883007989d373d19f352ba9792b25dec21dc7d0e205a710a93a3815101bb3d03",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/javax/xml/bind/jaxb-api/2.3.0/jaxb-api-2.3.0.jar",
            "http://repo1.maven.org/maven2/javax/xml/bind/jaxb-api/2.3.0/jaxb-api-2.3.0.jar",
        ],
        # CDDL 1.1 or GPLv2 w/ CPE (We choo-choo-choose the CDDL)
        # https://glassfish.java.net/public/CDDL+GPL_1_1.html
        licenses = ["reciprocal"],
    )

def javax_xml_soap_api():
    java_import_external(
        name = "javax_xml_soap_api",
        licenses = ["restricted"],  # CDDL + GPLv2 with classpath exception
        jar_sha256 = "141374e33be99768611a2d42b9d33571a0c5b9763beca9c2dc90900d8cc8f767",
        jar_urls = [
            "http://repo1.maven.org/maven2/javax/xml/soap/javax.xml.soap-api/1.4.0/javax.xml.soap-api-1.4.0.jar",
            "http://maven.ibiblio.org/maven2/javax/xml/soap/javax.xml.soap-api/1.4.0/javax.xml.soap-api-1.4.0.jar",
        ],
    )

def jline():
    java_import_external(
        name = "jline",
        licenses = ["notice"],  # BSD
        jar_sha256 = "b0d884980fab1df2f948c568f576c365f3379dc8bc930272fa508843d1f3652b",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/jline/jline/1.0/jline-1.0.jar",
            "http://repo1.maven.org/maven2/jline/jline/1.0/jline-1.0.jar",
        ],
    )

def joda_time():
    java_import_external(
        name = "joda_time",
        jar_sha256 = "602fd8006641f8b3afd589acbd9c9b356712bdcf0f9323557ec8648cd234983b",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/joda-time/joda-time/2.3/joda-time-2.3.jar",
            "http://repo1.maven.org/maven2/joda-time/joda-time/2.3/joda-time-2.3.jar",
        ],
        licenses = ["notice"],  # Apache 2
    )

def junit():
    java_import_external(
        name = "junit",
        jar_sha256 = "59721f0805e223d84b90677887d9ff567dc534d7c502ca903c0c2b17f05c116a",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/junit/junit/4.12/junit-4.12.jar",
            "http://repo1.maven.org/maven2/junit/junit/4.12/junit-4.12.jar",
        ],
        licenses = ["reciprocal"],  # Common Public License Version 1.0
        testonly_ = True,
        exports = ["@org_hamcrest_core"],
    )

def net_bytebuddy():
    java_import_external(
        name = "net_bytebuddy",
        jar_sha256 = "69a9140c11de463789a1badfe6c3dcdc17608c4304cb443c5c3a179585b78b39",
        jar_urls = [
            "http://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/1.9.7/byte-buddy-1.9.7.jar",
        ],
        licenses = ["notice"],  # Apache 2
        testonly_ = True,
    )

def org_apache_avro():
    java_import_external(
        name = "org_apache_avro",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "f754a0830ce67a5a9fa67a54ec15d103ef15e1c850d7b26faf7b647eeddc82d3",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/avro/avro/1.8.2/avro-1.8.2.jar",
            "http://repo1.maven.org/maven2/org/apache/avro/avro/1.8.2/avro-1.8.2.jar",
        ],
        deps = [
            "@org_codehaus_jackson_core_asl",
            "@org_codehaus_jackson_mapper_asl",
            "@com_thoughtworks_paranamer",
            "@org_xerial_snappy_java",
            "@org_apache_commons_compress",
            "@org_tukaani_xz",
            "@org_slf4j_api",
        ],
    )

def org_apache_beam_runners_direct_java():
    java_import_external(
        name = "org_apache_beam_runners_direct_java",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "f394ad1577c2af67417af27305c9efd50de268d23629171fd2c7813f8d385713",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/beam/beam-runners-direct-java/2.2.0/beam-runners-direct-java-2.2.0.jar",
            "http://repo1.maven.org/maven2/org/apache/beam/beam-runners-direct-java/2.2.0/beam-runners-direct-java-2.2.0.jar",
        ],
        deps = [
            "@org_apache_beam_sdks_java_core",
            "@joda_time",
            "@org_slf4j_api",
            "@com_google_auto_value",
            "@org_hamcrest_all",
        ],
    )

def org_apache_beam_runners_google_cloud_dataflow_java():
    java_import_external(
        name = "org_apache_beam_runners_google_cloud_dataflow_java",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "244e9adee402aa6b56fd29bc358db16c5f027d0d9c9677960f0c64d8f27ed1f1",
        jar_urls = [
            "http://repo1.maven.org/maven2/org/apache/beam/beam-runners-google-cloud-dataflow-java/2.1.0/beam-runners-google-cloud-dataflow-java-2.1.0.jar",
            "http://maven.ibiblio.org/maven2/org/apache/beam/beam-runners-google-cloud-dataflow-java/2.1.0/beam-runners-google-cloud-dataflow-java-2.1.0.jar",
        ],
        deps = [
            "@org_apache_beam_sdks_java_core",
            "@org_apache_beam_sdks_java_extensions_google_cloud_platform_core",
            "@org_apache_beam_sdks_common_runner_api",
            "@org_apache_beam_sdks_java_io_google_cloud_platform",
            "@com_google_api_client",
            "@com_google_http_client",
            "@com_google_http_client_jackson2",
            "@com_google_apis_google_api_services_dataflow",
            "@com_google_apis_google_api_services_clouddebugger",
            "@com_google_apis_google_api_services_storage",
            "@com_google_auth_library_credentials",
            "@com_google_auth_library_oauth2_http",
            "@com_google_cloud_bigdataoss_util",
            "@org_apache_avro",
            "@joda_time",
            "@com_google_code_findbugs_jsr305",
            "@com_fasterxml_jackson_core",
            "@com_fasterxml_jackson_core_jackson_annotations",
            "@com_fasterxml_jackson_core_jackson_databind",
            "@org_slf4j_api",
            "@com_google_auto_value",
            "@org_hamcrest_all",
            #"@junit",
        ],
    )

def org_apache_beam_sdks_common_runner_api():
    java_import_external(
        name = "org_apache_beam_sdks_common_runner_api",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "8af98f525b86bd8efc6c25f0008573c877b040b04d9e4b3284d12c86e12c7593",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/beam/beam-sdks-common-runner-api/2.1.0/beam-sdks-common-runner-api-2.1.0.jar",
            "http://repo1.maven.org/maven2/org/apache/beam/beam-sdks-common-runner-api/2.1.0/beam-sdks-common-runner-api-2.1.0.jar",
        ],
    )

def org_apache_beam_sdks_java_core():
    java_import_external(
        name = "org_apache_beam_sdks_java_core",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "814e94d9de8d41853281e488889b5dec6fba01ce434447dda091b8ca109a36f8",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/beam/beam-sdks-java-core/2.2.0/beam-sdks-java-core-2.2.0.jar",
            "http://repo1.maven.org/maven2/org/apache/beam/beam-sdks-java-core/2.2.0/beam-sdks-java-core-2.2.0.jar",
        ],
        deps = [
            "@com_google_code_findbugs_jsr305",
            "@com_fasterxml_jackson_core",
            "@com_fasterxml_jackson_core_jackson_annotations",
            "@com_fasterxml_jackson_core_jackson_databind",
            "@org_slf4j_api",
            "@org_apache_avro",
            "@org_xerial_snappy_java",
            "@joda_time",
            "@com_google_auto_value",
            "@org_hamcrest_all",
        ],
    )

def org_apache_beam_sdks_java_extensions_google_cloud_platform_core():
    java_import_external(
        name = "org_apache_beam_sdks_java_extensions_google_cloud_platform_core",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "89cc7f65ef9f446f60145f17da32d454196a2771282823aa732d2bdd5b3b39c9",
        jar_urls = [
            "http://repo1.maven.org/maven2/org/apache/beam/beam-sdks-java-extensions-google-cloud-platform-core/2.1.0/beam-sdks-java-extensions-google-cloud-platform-core-2.1.0.jar",
            "http://maven.ibiblio.org/maven2/org/apache/beam/beam-sdks-java-extensions-google-cloud-platform-core/2.1.0/beam-sdks-java-extensions-google-cloud-platform-core-2.1.0.jar",
        ],
        deps = [
            "@org_apache_beam_sdks_java_core",
            "@com_google_http_client_jackson2",
            "@com_google_auth_library_oauth2_http",
            "@com_google_api_client",
            "@com_google_cloud_bigdataoss_gcsio",
            "@com_google_cloud_bigdataoss_util",
            "@com_google_apis_google_api_services_cloudresourcemanager",
            "@com_google_apis_google_api_services_storage",
            "@com_google_auth_library_credentials",
            "@com_google_code_findbugs_jsr305",
            "@com_google_http_client",
            "@org_slf4j_api",
            "@joda_time",
            "@com_fasterxml_jackson_core_jackson_annotations",
            "@com_fasterxml_jackson_core_jackson_databind",
            "@com_google_auto_value",
            "@org_hamcrest_all",
            #"@junit",
        ],
    )

def org_apache_beam_sdks_java_io_google_cloud_platform():
    java_import_external(
        name = "org_apache_beam_sdks_java_io_google_cloud_platform",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "7b94b19c5ff79e7a0cccf8ae3556b728643fc7b6c23a6fa21806795bbc69ce9a",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/beam/beam-sdks-java-io-google-cloud-platform/2.2.0/beam-sdks-java-io-google-cloud-platform-2.2.0.jar",
            "http://repo1.maven.org/maven2/org/apache/beam/beam-sdks-java-io-google-cloud-platform/2.2.0/beam-sdks-java-io-google-cloud-platform-2.2.0.jar",
        ],
        deps = [
            "@org_apache_beam_sdks_java_extensions_google_cloud_platform_core",
            "@com_fasterxml_jackson_core_jackson_databind",
            "@com_google_api_client",
            "@com_google_http_client_jackson2",
            "@com_google_auth_library_oauth2_http",
            "@com_google_guava",
            "@com_google_code_findbugs_jsr305",
            "@org_apache_avro",
            #"@junit",
        ],
    )

def org_apache_commons_compress():
    java_import_external(
        name = "org_apache_commons_compress",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "5fca136503f86ecc6cb61fbd17b137d59e56b45c7a5494e6b8fd3cabd4697fbd",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/commons/commons-compress/1.8.1/commons-compress-1.8.1.jar",
            "http://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.8.1/commons-compress-1.8.1.jar",
            "http://maven.ibiblio.org/maven2/org/apache/commons/commons-compress/1.8.1/commons-compress-1.8.1.jar",
        ],
    )

def org_apache_ftpserver_core():
    java_import_external(
        name = "org_apache_ftpserver_core",
        jar_sha256 = "e0b6df55cca376c65a8969e4d9ed72a92c9bf0780ee077a03ff728e07314edcb",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/ftpserver/ftpserver-core/1.0.6/ftpserver-core-1.0.6.jar",
            "http://repo1.maven.org/maven2/org/apache/ftpserver/ftpserver-core/1.0.6/ftpserver-core-1.0.6.jar",
        ],
        licenses = ["notice"],  # Apache 2.0 License
        deps = [
            "@org_slf4j_api",
            "@org_apache_mina_core",
        ],
    )

def org_apache_httpcomponents_httpclient():
    java_import_external(
        name = "org_apache_httpcomponents_httpclient",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "7e97724443ad2a25ad8c73183431d47cc7946271bcbbdfa91a8a17522a566573",
        jar_urls = [
            "http://repo1.maven.org/maven2/org/apache/httpcomponents/httpclient/4.5.5/httpclient-4.5.5.jar",
            "http://maven.ibiblio.org/maven2/org/apache/httpcomponents/httpclient/4.5.5/httpclient-4.5.5.jar",
        ],
        deps = [
            "@org_apache_httpcomponents_httpcore",
            "@commons_logging",
            "@commons_codec",
        ],
    )

def org_apache_httpcomponents_httpcore():
    java_import_external(
        name = "org_apache_httpcomponents_httpcore",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "1b4a1c0b9b4222eda70108d3c6e2befd4a6be3d9f78ff53dd7a94966fdf51fc5",
        jar_urls = [
            "http://repo1.maven.org/maven2/org/apache/httpcomponents/httpcore/4.4.9/httpcore-4.4.9.jar",
            "http://maven.ibiblio.org/maven2/org/apache/httpcomponents/httpcore/4.4.9/httpcore-4.4.9.jar",
        ],
    )

def org_apache_mina_core():
    java_import_external(
        name = "org_apache_mina_core",
        jar_sha256 = "f6e37603b0ff1b50b31c1be7e5815098d78aff1f277db27d3aee5d7e8cce636e",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/mina/mina-core/2.0.4/mina-core-2.0.4.jar",
            "http://repo1.maven.org/maven2/org/apache/mina/mina-core/2.0.4/mina-core-2.0.4.jar",
        ],
        licenses = ["notice"],  # Apache 2.0 License
    )

def org_apache_sshd_core():
    java_import_external(
        name = "org_apache_sshd_core",
        # Apache 2.0 License
        # http://www.apache.org/licenses/LICENSE-2.0
        # Apache License, Version 2.0
        # http://www.apache.org/licenses/LICENSE-2.0.txt
        licenses = ["notice"],
        jar_sha256 = "00c944fac00dec2e7ace4052e0a52c772ca3fa2653918bbcfadf7100df022e25",
        jar_urls = [
            "http://repo1.maven.org/maven2/org/apache/sshd/sshd-core/2.0.0/sshd-core-2.0.0.jar",
            "http://maven.ibiblio.org/maven2/org/apache/sshd/sshd-core/2.0.0/sshd-core-2.0.0.jar",
        ],
        deps = ["@org_slf4j_api"],
    )

def org_apache_sshd_scp():
    java_import_external(
        name = "org_apache_sshd_scp",
        # Apache 2.0 License
        # http://www.apache.org/licenses/LICENSE-2.0
        # Apache License, Version 2.0
        # http://www.apache.org/licenses/LICENSE-2.0.txt
        licenses = ["notice"],
        jar_sha256 = "ae32fcc16ab0a0ae04655b8832676b41199814184dc50028b3c6aa61053635ca",
        jar_urls = [
            "http://repo1.maven.org/maven2/org/apache/sshd/sshd-scp/2.0.0/sshd-scp-2.0.0.jar",
            "http://maven.ibiblio.org/maven2/org/apache/sshd/sshd-scp/2.0.0/sshd-scp-2.0.0.jar",
        ],
        deps = ["@org_apache_sshd_core"],
    )

def org_apache_sshd_sftp():
    java_import_external(
        name = "org_apache_sshd_sftp",
        # Apache 2.0 License
        # http://www.apache.org/licenses/LICENSE-2.0
        # Apache License, Version 2.0
        # http://www.apache.org/licenses/LICENSE-2.0.txt
        licenses = ["notice"],
        jar_sha256 = "0504af9a4afcaf61be9f0b56d3cfc76a9187a654e297bc57b7fa81aa76bb8cb0",
        jar_urls = [
            "http://repo1.maven.org/maven2/org/apache/sshd/sshd-sftp/2.0.0/sshd-sftp-2.0.0.jar",
            "http://maven.ibiblio.org/maven2/org/apache/sshd/sshd-sftp/2.0.0/sshd-sftp-2.0.0.jar",
        ],
        deps = ["@org_apache_sshd_core"],
    )

def org_apache_tomcat_servlet_api():
    java_import_external(
        name = "org_apache_tomcat_servlet_api",
        jar_sha256 = "8df016b101b7e5f24940dbbcdf03b8b6b1544462ec6af97a92d3bbf3641153b9",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/tomcat/servlet-api/6.0.45/servlet-api-6.0.45.jar",
            "http://repo1.maven.org/maven2/org/apache/tomcat/servlet-api/6.0.45/servlet-api-6.0.45.jar",
        ],
        licenses = ["reciprocal"],  # Apache License, Version 2.0 and Common Development And Distribution License (CDDL) Version 1.0
    )

def org_apache_tomcat_annotations_api():
    java_import_external(
        name = "org_apache_tomcat_annotations_api",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "748677bebb1651a313317dfd93e984ed8f8c9e345538fa8b0ab0cbb804631953",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/apache/tomcat/tomcat-annotations-api/8.0.5/tomcat-annotations-api-8.0.5.jar",
            "http://repo1.maven.org/maven2/org/apache/tomcat/tomcat-annotations-api/8.0.5/tomcat-annotations-api-8.0.5.jar",
        ],
    )

def org_bouncycastle_bcpg_jdk15on():
    java_import_external(
        name = "org_bouncycastle_bcpg_jdk15on",
        jar_sha256 = "eb3c3744c9ad775a7afd03e9dfd3d34786c11832a93ea1143b97cc88b0344154",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/bouncycastle/bcpg-jdk15on/1.52/bcpg-jdk15on-1.52.jar",
            "http://repo1.maven.org/maven2/org/bouncycastle/bcpg-jdk15on/1.52/bcpg-jdk15on-1.52.jar",
        ],
        # Bouncy Castle Licence
        # http://www.bouncycastle.org/licence.html
        # Apache Software License, Version 1.1
        # http://www.apache.org/licenses/LICENSE-1.1
        licenses = ["notice"],
        deps = ["@org_bouncycastle_bcprov_jdk15on"],
    )

def org_bouncycastle_bcpkix_jdk15on():
    java_import_external(
        name = "org_bouncycastle_bcpkix_jdk15on",
        jar_sha256 = "8e8e9ac258051ec8d6f7f1128d0ddec800ed87b14e7a55023d0f2850b8049615",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/bouncycastle/bcpkix-jdk15on/1.52/bcpkix-jdk15on-1.52.jar",
            "http://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk15on/1.52/bcpkix-jdk15on-1.52.jar",
        ],
        licenses = ["notice"],  # Bouncy Castle Licence
        exports = ["@org_bouncycastle_bcprov_jdk15on"],
        deps = ["@org_bouncycastle_bcprov_jdk15on"],
    )

def org_bouncycastle_bcprov_jdk15on():
    java_import_external(
        name = "org_bouncycastle_bcprov_jdk15on",
        jar_sha256 = "0dc4d181e4d347893c2ddbd2e6cd5d7287fc651c03648fa64b2341c7366b1773",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/bouncycastle/bcprov-jdk15on/1.52/bcprov-jdk15on-1.52.jar",
            "http://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/1.52/bcprov-jdk15on-1.52.jar",
        ],
        licenses = ["notice"],  # Bouncy Castle Licence
    )

def org_codehaus_jackson_core_asl():
    java_import_external(
        name = "org_codehaus_jackson_core_asl",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "440a9cb5ca95b215f953d3a20a6b1a10da1f09b529a9ddea5f8a4905ddab4f5a",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/codehaus/jackson/jackson-core-asl/1.9.13/jackson-core-asl-1.9.13.jar",
            "http://repo1.maven.org/maven2/org/codehaus/jackson/jackson-core-asl/1.9.13/jackson-core-asl-1.9.13.jar",
        ],
    )

def org_codehaus_jackson_mapper_asl():
    java_import_external(
        name = "org_codehaus_jackson_mapper_asl",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "74e7a07a76f2edbade29312a5a2ebccfa019128bc021ece3856d76197e9be0c2",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/codehaus/jackson/jackson-mapper-asl/1.9.13/jackson-mapper-asl-1.9.13.jar",
            "http://repo1.maven.org/maven2/org/codehaus/jackson/jackson-mapper-asl/1.9.13/jackson-mapper-asl-1.9.13.jar",
        ],
        deps = ["@org_codehaus_jackson_core_asl"],
    )

def org_hamcrest_all():
    java_import_external(
        name = "org_hamcrest_all",
        neverlink = 1,
        licenses = ["notice"],  # New BSD License
        jar_sha256 = "4877670629ab96f34f5f90ab283125fcd9acb7e683e66319a68be6eb2cca60de",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/hamcrest/hamcrest-all/1.3/hamcrest-all-1.3.jar",
            "http://repo1.maven.org/maven2/org/hamcrest/hamcrest-all/1.3/hamcrest-all-1.3.jar",
            "http://maven.ibiblio.org/maven2/org/hamcrest/hamcrest-all/1.3/hamcrest-all-1.3.jar",
        ],
    )

def org_hamcrest_core():
    java_import_external(
        name = "org_hamcrest_core",
        jar_sha256 = "66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
            "http://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
        ],
        licenses = ["notice"],  # New BSD License
        #testonly_ = True,
    )

def org_hamcrest_library():
    java_import_external(
        name = "org_hamcrest_library",
        jar_sha256 = "711d64522f9ec410983bd310934296da134be4254a125080a0416ec178dfad1c",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/hamcrest/hamcrest-library/1.3/hamcrest-library-1.3.jar",
            "http://repo1.maven.org/maven2/org/hamcrest/hamcrest-library/1.3/hamcrest-library-1.3.jar",
        ],
        licenses = ["notice"],  # New BSD License
        testonly_ = True,
        deps = ["@org_hamcrest_core"],
    )

def org_joda_money():
    java_import_external(
        name = "org_joda_money",
        jar_sha256 = "d530b7f0907d91f5c98f25e91eb89ad164845412700be36b07652c07512ef8d4",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/joda/joda-money/0.10.0/joda-money-0.10.0.jar",
            "http://repo1.maven.org/maven2/org/joda/joda-money/0.10.0/joda-money-0.10.0.jar",
        ],
        licenses = ["notice"],  # Apache 2
    )

def org_json():
    java_import_external(
        name = "org_json",
        jar_sha256 = "bf51c9013128cb15201225e51476f60ad9116813729040655a238d2829aef8b8",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/json/json/20160810/json-20160810.jar",
            "http://repo1.maven.org/maven2/org/json/json/20160810/json-20160810.jar",
        ],
        licenses = ["notice"],  # The JSON License
    )

def org_khronos_opengl_api():
    java_import_external(
        name = "org_khronos_opengl_api",
        licenses = ["notice"],  # Apache 2.0
        jar_sha256 = "cc08393220f706ef9299b453cd88f741382cee49dbeea2c667b4be1435768145",
        jar_urls = [
            "http://repo1.maven.org/maven2/org/khronos/opengl-api/gl1.1-android-2.1_r1/opengl-api-gl1.1-android-2.1_r1.jar",
            "http://maven.ibiblio.org/maven2/org/khronos/opengl-api/gl1.1-android-2.1_r1/opengl-api-gl1.1-android-2.1_r1.jar",
        ],
    )

def org_mockito_core():
    java_import_external(
        name = "org_mockito_core",
        jar_sha256 = "28028d70cc27d61442948fcb3d249d9df5b37c47aa0b82490a3d049094ff411f",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/mockito/mockito-core/2.25.0/mockito-core-2.25.0.jar",
            "http://repo1.maven.org/maven2/org/mockito/mockito-core/2.25.0/mockito-core-2.25.0.jar",
        ],
        licenses = ["notice"],  # The MIT License
        testonly_ = True,
        deps = [
            "@net_bytebuddy",
        ],
        exports = ["@org_hamcrest_all", "@org_hamcrest_library", "@org_objenesis_objenesis"],
    )

def org_mortbay_jetty():
    java_import_external(
        name = "org_mortbay_jetty",
        jar_sha256 = "21091d3a9c1349f640fdc421504a604c040ed89087ecc12afbe32353326ed4e5",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/mortbay/jetty/jetty/6.1.26/jetty-6.1.26.jar",
            "http://repo1.maven.org/maven2/org/mortbay/jetty/jetty/6.1.26/jetty-6.1.26.jar",
        ],
        # Apache Software License - Version 2.0
        # http://www.apache.org/licenses/LICENSE-2.0
        # Eclipse Public License - Version 1.0
        # http://www.eclipse.org/org/documents/epl-v10.php
        licenses = ["notice"],
        deps = [
            "@org_mortbay_jetty_util",
            "@org_mortbay_jetty_servlet_api",
        ],
    )

def org_mortbay_jetty_servlet_api():
    java_import_external(
        name = "org_mortbay_jetty_servlet_api",
        jar_sha256 = "068756096996fe00f604ac3b6672d6f663dc777ea4a83056e240d0456e77e472",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/mortbay/jetty/servlet-api/2.5-20081211/servlet-api-2.5-20081211.jar",
            "http://repo1.maven.org/maven2/org/mortbay/jetty/servlet-api/2.5-20081211/servlet-api-2.5-20081211.jar",
        ],
        licenses = ["notice"],  # Apache License Version 2.0
    )

def org_mortbay_jetty_util():
    java_import_external(
        name = "org_mortbay_jetty_util",
        jar_sha256 = "9b974ce2b99f48254b76126337dc45b21226f383aaed616f59780adaf167c047",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/mortbay/jetty/jetty-util/6.1.26/jetty-util-6.1.26.jar",
            "http://repo1.maven.org/maven2/org/mortbay/jetty/jetty-util/6.1.26/jetty-util-6.1.26.jar",
        ],
        # Apache Software License - Version 2.0
        # http://www.apache.org/licenses/LICENSE-2.0
        # Eclipse Public License - Version 1.0
        # http://www.eclipse.org/org/documents/epl-v10.php
        licenses = ["notice"],
        deps = ["@org_mortbay_jetty_servlet_api"],
    )

def org_objenesis_objenesis():
    java_import_external(
        name = "org_objenesis_objenesis",
        licenses = ["notice"],  # Apache 2
        testonly_ = True,
        jar_sha256 = "5e168368fbc250af3c79aa5fef0c3467a2d64e5a7bd74005f25d8399aeb0708d",
        jar_urls = [
            "http://repo1.maven.org/maven2/org/objenesis/objenesis/2.6/objenesis-2.6.jar",
            "http://maven.ibiblio.org/maven2/org/objenesis/objenesis/2.6/objenesis-2.6.jar",
        ],
    )

def org_osgi_core():
    java_import_external(
        name = "org_osgi_core",
        neverlink = 1,
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "76c1f055f04987d1dc59f3efea30e548376ef7b9dadf2256b3f39600855541ab",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/osgi/org.osgi.core/4.3.0/org.osgi.core-4.3.0.jar",
            "http://repo1.maven.org/maven2/org/osgi/org.osgi.core/4.3.0/org.osgi.core-4.3.0.jar",
        ],
    )

def org_slf4j_api():
    java_import_external(
        name = "org_slf4j_api",
        jar_sha256 = "e56288031f5e60652c06e7bb6e9fa410a61231ab54890f7b708fc6adc4107c5b",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/slf4j/slf4j-api/1.7.16/slf4j-api-1.7.16.jar",
            "http://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.16/slf4j-api-1.7.16.jar",
        ],
        licenses = ["notice"],  # MIT License
    )

def org_tukaani_xz():
    java_import_external(
        name = "org_tukaani_xz",
        licenses = ["unencumbered"],  # Public Domain
        jar_sha256 = "86f30fa8775fa3a62cdb39d1ed78a6019164c1058864048d42cbee244e26e840",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/tukaani/xz/1.5/xz-1.5.jar",
            "http://repo1.maven.org/maven2/org/tukaani/xz/1.5/xz-1.5.jar",
        ],
    )

def org_xerial_snappy_java():
    java_import_external(
        name = "org_xerial_snappy_java",
        licenses = ["notice"],  # The Apache Software License, Version 2.0
        jar_sha256 = "18563b50e6691b37ab9428ef639a0f66def91337b3c0d9b3dcf20b0dd7ae78ba",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/xerial/snappy/snappy-java/1.1.4-M3/snappy-java-1.1.4-M3.jar",
            "http://repo1.maven.org/maven2/org/xerial/snappy/snappy-java/1.1.4-M3/snappy-java-1.1.4-M3.jar",
        ],
        deps = ["@org_osgi_core"],
    )

def org_yaml_snakeyaml():
    java_import_external(
        name = "org_yaml_snakeyaml",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "5666b36f9db46f06dd5a19d73bbff3b588d5969c0f4b8848fde0f5ec849430a5",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/org/yaml/snakeyaml/1.17/snakeyaml-1.17.jar",
            "http://repo1.maven.org/maven2/org/yaml/snakeyaml/1.17/snakeyaml-1.17.jar",
        ],
    )

def xerces_xmlParserAPIs():
    java_import_external(
        name = "xerces_xmlParserAPIs",
        licenses = ["notice"],  # Apache License, Version 2.0
        jar_sha256 = "1c2867be1faa73c67e9232631241eb1df4cd0763048646e7bb575a9980e9d7e5",
        jar_urls = [
            "http://repo1.maven.org/maven2/xerces/xmlParserAPIs/2.6.2/xmlParserAPIs-2.6.2.jar",
            "http://maven.ibiblio.org/maven2/xerces/xmlParserAPIs/2.6.2/xmlParserAPIs-2.6.2.jar",
        ],
    )

def xpp3():
    java_import_external(
        name = "xpp3",
        # Indiana University Extreme! Lab Software License, vesion 1.1.1
        # http://www.extreme.indiana.edu/viewcvs/~checkout~/XPP3/java/LICENSE.txt
        # Public Domain
        # http://creativecommons.org/licenses/publicdomain
        # Apache Software License, version 1.1
        # http://www.apache.org/licenses/LICENSE-1.1
        licenses = ["notice"],
        jar_sha256 = "0341395a481bb887803957145a6a37879853dd625e9244c2ea2509d9bb7531b9",
        jar_urls = [
            "http://maven.ibiblio.org/maven2/xpp3/xpp3/1.1.4c/xpp3-1.1.4c.jar",
            "http://repo1.maven.org/maven2/xpp3/xpp3/1.1.4c/xpp3-1.1.4c.jar",
        ],
    )

def _check_bazel_version(project, bazel_version):
    if "bazel_version" not in dir(native):
        fail("%s requires Bazel >=%s but was <0.2.1" % (project, bazel_version))
    elif not native.bazel_version:
        pass  # user probably compiled Bazel from scratch
    else:
        current_bazel_version = _parse_bazel_version(native.bazel_version)
        minimum_bazel_version = _parse_bazel_version(bazel_version)
        if minimum_bazel_version > current_bazel_version:
            fail("%s requires Bazel >=%s but was %s" % (
                project,
                bazel_version,
                native.bazel_version,
            ))

def _parse_bazel_version(bazel_version):
    # Remove commit from version.
    version = bazel_version.split(" ", 1)[0]

    # Split into (release, date) parts and only return the release
    # as a tuple of integers.
    parts = version.split("-", 1)

    # Turn "release" into a tuple of ints.
    version_tuple = ()
    for number in parts[0].split("."):
        version_tuple += (int(number),)
    return version_tuple
