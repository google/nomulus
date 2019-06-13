package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "dns",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/cron",
        "//java/google/registry/dns",
        "//java/google/registry/dns:constants",
        "//java/google/registry/dns/writer",
        "//java/google/registry/model",
        "//java/google/registry/module/backend",
        "//java/google/registry/request",
        "//java/google/registry/request/lock",
        "//java/google/registry/util",
        "//javatests/google/registry/testing",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_api_stubs",
        "@com_google_dagger",
        "@com_google_guava",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@javax_servlet_api",
        "@joda_time",
        "@junit",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":dns"],
)
