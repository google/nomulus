package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "invoicing",
    srcs = glob(["*.java"]),
    resources = glob(["sql/*"]),
    deps = [
        "//java/google/registry/beam",
        "//java/google/registry/config",
        "//java/google/registry/model",
        "//java/google/registry/reporting/billing",
        "//java/google/registry/util",
        "@com_google_apis_google_api_services_bigquery",
        "@com_google_auth_library_oauth2_http",
        "@com_google_auto_value",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@javax_inject",
        "@org_apache_avro",
        "@org_apache_beam_runners_direct_java",
        "@org_apache_beam_runners_google_cloud_dataflow_java",
        "@org_apache_beam_sdks_java_core",
        "@org_apache_beam_sdks_java_io_google_cloud_platform",
    ],
)
