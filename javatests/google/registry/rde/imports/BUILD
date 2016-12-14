package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "imports",
    srcs = glob(["*.java"]),
    resources = glob(["testdata/*"]),
    deps = [
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/google/registry/config",
        "//java/google/registry/gcs",
        "//java/google/registry/mapreduce",
        "//java/google/registry/model",
        "//java/google/registry/rde/imports",
        "//java/google/registry/request",
        "//java/google/registry/util",
        "//java/google/registry/xjc",
        "//javatests/google/registry/testing",
        "//javatests/google/registry/testing/mapreduce",
        "//javatests/google/registry/xml",
        "//third_party/java/appengine:appengine-api-testonly",
        "//third_party/java/appengine_gcs_client",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/junit",
        "//third_party/java/mockito",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/truth",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    default_test_size = "medium",
    shard_count = 4,
    test_files = glob(["*Test.java"]),
    deps = [":imports"],
)
