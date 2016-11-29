package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "idn",
    srcs = glob([
        "*.java",
    ]),
    deps = [
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/google/registry/tldconfig/idn",
        "//javatests/google/registry/testing",
        "//third_party/java/junit",
        "//third_party/java/truth",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":idn"],
)
