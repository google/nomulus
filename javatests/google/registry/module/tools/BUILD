package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "tools",
    srcs = glob(["*.java"]),
    resources = glob(["testdata/*"]),
    runtime_deps = [
        # TODO(b/19332643): Remove this dependency when Modules is lazy.
        "//third_party/java/appengine:appengine-api-testonly",
    ],
    deps = [
        "//java/google/registry/module/tools",
        "//third_party/java/junit",
        "//third_party/java/mockito",
        "//third_party/java/servlet/servlet_api",
        "//third_party/java/truth",
    ],
)

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":tools"],
)
