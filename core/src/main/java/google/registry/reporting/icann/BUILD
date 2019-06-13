package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "icann",
    srcs = glob(["*.java"]),
    resources = glob(["sql/*"]),
    deps = [
        "//java/google/registry/bigquery",
        "//java/google/registry/config",
        "//java/google/registry/gcs",
        "//java/google/registry/keyring/api",
        "//java/google/registry/model",
        "//java/google/registry/reporting",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/util",
        "//java/google/registry/xjc",
        "//java/google/registry/xml",
        "@com_google_api_client",
        "@com_google_apis_google_api_services_bigquery",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_http_client",
        "@com_google_http_client_jackson2",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
    ],
)
