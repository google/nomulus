package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "model",
    # There be circular dependencies down thar, so just have this
    # BUILD file glob them in.
    srcs = glob([
        "*.java",
        "**/*.java",
    ]),
    resources = [
        "schema.txt",
    ] + glob(["**/testdata/*.xml"]),
    deps = [
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/com/google/common/testing",
        "//java/com/google/common/util/concurrent",
        "//java/google/registry/config",
        "//java/google/registry/flows",
        "//java/google/registry/model",
        "//java/google/registry/util",
        "//java/google/registry/xml",
        "//javatests/google/registry/testing",
        "//javatests/google/registry/xml",
        "//third_party/java/appengine:appengine-api-testonly",
        "//third_party/java/appengine:appengine-testing",
        "//third_party/java/joda_money",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/junit",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/truth",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    medium_tests = ["registry/label/PremiumListTest"],
    test_files = glob([
        "*Test.java",
        "**/*Test.java",
    ]),
    deps = [":model"],
)
