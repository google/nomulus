package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "spec11",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/beam/spec11",
        "//java/google/registry/config",
        "//java/google/registry/gcs",
        "//java/google/registry/keyring/api",
        "//java/google/registry/model",
        "//java/google/registry/reporting",
        "//java/google/registry/reporting/spec11/soy:soy_java_wrappers",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/util",
        "@com_google_api_client_appengine",
        "@com_google_apis_google_api_services_dataflow",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_auto_value",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_http_client",
        "@io_bazel_rules_closure//closure/templates",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
        "@org_apache_beam_runners_direct_java",
        "@org_apache_beam_runners_google_cloud_dataflow_java",
        "@org_apache_beam_sdks_java_core",
        "@org_apache_beam_sdks_java_io_google_cloud_platform",
        "@org_json",
    ],
)
