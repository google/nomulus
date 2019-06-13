package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "tools",
    srcs = glob([
        "*.java",
    ]),
    resources = glob([
        "testdata/*.*",
        "server/testdata/*.*",
    ]),
    deps = [
        "//java/google/registry/backup",
        "//java/google/registry/config",
        "//java/google/registry/export/datastore",
        "//java/google/registry/flows",
        "//java/google/registry/keyring/api",
        "//java/google/registry/model",
        "//java/google/registry/rde",
        "//java/google/registry/request",
        "//java/google/registry/security",
        "//java/google/registry/tmch",
        "//java/google/registry/tools",
        "//java/google/registry/tools/params",
        "//java/google/registry/tools/server",
        "//java/google/registry/util",
        "//java/google/registry/xml",
        "//javatests/google/registry/flows",
        "//javatests/google/registry/rde",
        "//javatests/google/registry/testing",
        "//javatests/google/registry/tmch",
        "//javatests/google/registry/tools/server",
        "//javatests/google/registry/xml",
        "//third_party/jaxb",
        "//third_party/objectify:objectify-v4_1",
        "@com_beust_jcommander",
        "@com_google_api_client",
        "@com_google_apis_google_api_services_appengine",
        "@com_google_apis_google_api_services_dns",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_remote_api",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_gson",
        "@com_google_guava",
        "@com_google_http_client",
        "@com_google_http_client_jackson2",
        "@com_google_oauth_client",
        "@com_google_re2j",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@com_googlecode_json_simple",
        "@joda_time",
        "@junit",
        "@org_joda_money",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["**/*Test.java"]),
    deps = [":tools"],
)
