package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "whitebox",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/bigquery",
        "//java/google/registry/model",
        "//java/google/registry/monitoring/whitebox",
        "//java/google/registry/util",
        "//javatests/google/registry/testing",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_apis_google_api_services_bigquery",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_api_stubs",
        "@com_google_appengine_testing",
        "@com_google_auto_value",
        "@com_google_guava",
        "@com_google_http_client",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@joda_time",
        "@junit",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":whitebox"],
)
