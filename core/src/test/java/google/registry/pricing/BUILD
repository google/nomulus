package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "pricing",
    srcs = glob(["*Test.java"]),
    deps = [
        "//java/google/registry/model",
        "//java/google/registry/pricing",
        "//java/google/registry/util",
        "//javatests/google/registry/testing",
        "@com_google_guava",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@joda_time",
        "@junit",
        "@org_joda_money",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":pricing"],
)
