package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "rde",
    srcs = glob(["*.java"]),
    resources = glob(["testdata/*"]),
    deps = [
        "//java/google/registry/gcs",
        "//java/google/registry/keyring/api",
        "//java/google/registry/model",
        "//java/google/registry/rde",
        "//java/google/registry/request",
        "//java/google/registry/tldconfig/idn",
        "//java/google/registry/util",
        "//java/google/registry/xjc",
        "//java/google/registry/xml",
        "//javatests/google/registry/testing",
        "//javatests/google/registry/testing/mapreduce",
        "//javatests/google/registry/testing/sftp",
        "//javatests/google/registry/xml",
        "//third_party/jaxb",
        "//third_party/jsch/v0_1_53",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_truth",
        "@com_google_truth_extensions_truth_java8_extension",
        "@javax_servlet_api",
        "@joda_time",
        "@junit",
        "@org_apache_ftpserver_core",
        "@org_bouncycastle_bcpg_jdk15on",
        "@org_bouncycastle_bcpkix_jdk15on",
        "@org_joda_money",
        "@org_mockito_core",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    default_test_size = "large",
    test_files = glob(
        ["*Test.java"],
        exclude = ["RdeUploadActionTest.java"],
    ),
    deps = [":rde"],
)
