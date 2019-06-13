package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "invoicing",
    srcs = glob(["*.java"]),
    resources = glob(["testdata/*"]),
    deps = [
        "//java/google/registry/beam/invoicing",
        "//java/google/registry/util",
        "//javatests/google/registry/testing",
        "@com_google_apis_google_api_services_bigquery",
        "@com_google_dagger",
        "@com_google_guava",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@junit",
        "@org_apache_avro",
        "@org_apache_beam_runners_direct_java",
        "@org_apache_beam_runners_google_cloud_dataflow_java",
        "@org_apache_beam_sdks_java_core",
        "@org_apache_beam_sdks_java_io_google_cloud_platform",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    default_test_size = "small",
    medium_tests = ["InvoicingPipelineTest.java"],
    test_files = glob(["*Test.java"]),
    deps = [":invoicing"],
)
