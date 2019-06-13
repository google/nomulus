package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "drive",
    srcs = glob(["*Test.java"]),
    deps = [
        "//java/google/registry/storage/drive",
        "//javatests/google/registry/testing",
        "@com_google_apis_google_api_services_drive",
        "@com_google_guava",
        "@com_google_http_client",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@junit",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":drive"],
)
