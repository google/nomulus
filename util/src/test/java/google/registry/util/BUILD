package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "util",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/util",
        "//javatests/google/registry/testing",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_code_findbugs_jsr305",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_guava_testlib",
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
    deps = [":util"],
)
