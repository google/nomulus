package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "request",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//javatests/google/registry/testing",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_guava",
        "@com_google_guava_testlib",
        "@com_google_monitoring_client_contrib",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@com_googlecode_json_simple",
        "@javax_servlet_api",
        "@joda_time",
        "@junit",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":request"],
)
