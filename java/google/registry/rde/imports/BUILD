package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "imports",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/dns",
        "//java/google/registry/gcs",
        "//java/google/registry/mapreduce",
        "//java/google/registry/model",
        "//java/google/registry/request",
        "//java/google/registry/util",
        "//java/google/registry/xjc",
        "//java/google/registry/xml",
        "//third_party/java/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_appengine_tools_appengine_gcs_client",
        "@com_google_appengine_tools_appengine_mapreduce",
        "@com_google_auto_factory",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_guava",
        "@javax_servlet_api",
        "@joda_time",
    ],
)
