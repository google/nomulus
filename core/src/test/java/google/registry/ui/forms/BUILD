package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "forms",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/ui/forms",
        "//javatests/google/registry/testing",
        "@com_google_guava",
        "@com_google_guava_testlib",
        "@com_google_re2j",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@junit",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":forms"],
)
