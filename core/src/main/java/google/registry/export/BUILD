package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "export",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/bigquery",
        "//java/google/registry/config",
        "//java/google/registry/export/datastore",
        "//java/google/registry/gcs",
        "//java/google/registry/groups",
        "//java/google/registry/mapreduce",
        "//java/google/registry/mapreduce/inputs",
        "//java/google/registry/model",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/storage/drive",
        "//java/google/registry/util",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_api_client",
        "@com_google_apis_google_api_services_bigquery",
        "@com_google_apis_google_api_services_drive",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_appengine_tools_appengine_mapreduce",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_http_client",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
    ],
)
