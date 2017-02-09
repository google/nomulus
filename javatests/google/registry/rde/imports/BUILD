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
        "//third_party/java/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk//:testonly",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_guava",
        "@com_google_truth",
        "@joda_time",
        "@junit",
        "@org_joda_money//:org_joda_money",
        "@org_mockito_all",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    default_test_size = "medium",
    shard_count = 4,
    test_files = glob(["*Test.java"]),
    deps = [":imports"],
)
