package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "reporting",
    srcs = glob(["*.java"]),
    resources = glob(["testdata/*"]),
    deps = [
        "//java/google/registry/reporting",
        "//java/google/registry/request",
        "//java/google/registry/util",
        "//javatests/google/registry/testing",
        "@com_google_apis_google_api_services_bigquery",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_dagger",
        "@com_google_http_client",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@javax_servlet_api",
        "@joda_time",
        "@junit",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    default_test_size = "small",
    test_files = glob(["*Test.java"]),
    deps = [":reporting"],
)
