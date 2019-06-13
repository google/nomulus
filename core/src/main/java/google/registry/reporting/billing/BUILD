package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "billing",
    srcs = glob(["*.java"]),
    runtime_deps = [
        "@com_google_apis_google_api_services_bigquery",
    ],
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/gcs",
        "//java/google/registry/model",
        "//java/google/registry/reporting",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/storage/drive",
        "//java/google/registry/util",
        "@com_google_api_client_appengine",
        "@com_google_apis_google_api_services_dataflow",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_http_client",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
        "@org_apache_beam_runners_direct_java",
        "@org_apache_beam_runners_google_cloud_dataflow_java",
        "@org_apache_beam_sdks_java_core",
        "@org_apache_beam_sdks_java_io_google_cloud_platform",
    ],
)
