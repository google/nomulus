package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "rdap",
    srcs = glob(["*.java"]),
    resources = glob(["testdata/*.json"]),
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/model",
        "//java/google/registry/rdap",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/ui/server/registrar",
        "//java/google/registry/util",
        "//javatests/google/registry/testing",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_gson",
        "@com_google_guava",
        "@com_google_http_client",
        "@com_google_monitoring_client_contrib",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@com_googlecode_json_simple",
        "@javax_servlet_api",
        "@joda_time",
        "@junit",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    medium_tests = ["RdapDomainSearchActionTest"],
    shard_count = 6,
    test_files = glob(["*Test.java"]),
    deps = [":rdap"],
)
