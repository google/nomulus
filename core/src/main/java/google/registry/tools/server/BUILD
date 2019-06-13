package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "server",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/dns",
        "//java/google/registry/export",
        "//java/google/registry/gcs",
        "//java/google/registry/groups",
        "//java/google/registry/mapreduce",
        "//java/google/registry/mapreduce/inputs",
        "//java/google/registry/model",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/util",
        "//java/google/registry/xml",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_appengine_tools_appengine_mapreduce",
        "@com_google_appengine_tools_appengine_pipeline",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
    ],
)
