package(
    default_testonly = 1,
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

TEST_CLASSES = ["AppEngineRuleTest.java"]

java_library(
    name = "testing",
    srcs = glob(
        ["*.java"],
        exclude = TEST_CLASSES,
    ),
    resources = [
        "logging.properties",
        "//java/google/registry:env/common/default/WEB-INF/datastore-indexes.xml",
        "//java/google/registry:env/common/default/WEB-INF/queue.xml",
    ] + glob(["*.csv"]) + glob(["testdata/*"]),
    exports = ["//third_party/junit"],
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/dns:constants",
        "//java/google/registry/dns/writer",
        "//java/google/registry/flows",
        "//java/google/registry/keyring/api",
        "//java/google/registry/model",
        "//java/google/registry/monitoring/whitebox",
        "//java/google/registry/pricing",
        "//java/google/registry/request",
        "//java/google/registry/request/lock",
        "//java/google/registry/tmch",
        "//java/google/registry/util",
        "//java/google/registry/xml",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_apis_google_api_services_appengine",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_api_stubs",
        "@com_google_appengine_testing",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_appengine_tools_sdk",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_guava_testlib",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
        "@junit",
        "@org_bouncycastle_bcpg_jdk15on",
        "@org_bouncycastle_bcpkix_jdk15on",
        "@org_joda_money",
        "@org_json",
        "@org_mockito_core",
    ],
)

java_library(
    name = "tests",
    srcs = TEST_CLASSES,
    deps = [
        ":testing",
        "@com_google_guava",
        "@junit",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    default_test_size = "small",
    test_files = glob(["*Test.java"]),
    deps = [
        ":tests",
    ],
)
