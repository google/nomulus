package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "xjc",
    srcs = glob(["*.java"]),
    resources = glob(["testdata/*.xml"]),
    deps = [
        "//java/google/registry/util",
        "//java/google/registry/xjc",
        "//javatests/google/registry/testing",
        "//third_party/java/hamcrest",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/junit",
        "//third_party/java/re2j",
        "//third_party/java/truth",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":xjc"],
)
