package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "request",
    srcs = glob(
        ["*.java"],
        exclude = ["Modules.java"],
    ),
    deps = [
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_guava",
        "@com_googlecode_json_simple",
        "@javax_servlet_api",
        "@joda_time",
        "//java/google/registry/security",
        "//java/google/registry/util",
    ],
)

java_library(
    name = "modules",
    srcs = ["Modules.java"],
    deps = [
        "@com_google_api_client",
        "@com_google_api_client_appengine",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_dagger",
        "@com_google_guava",
        "@com_google_http_client",
        "@com_google_http_client_appengine",
        "@com_google_http_client_jackson2",
        "//java/google/registry/config",
        "//java/google/registry/keyring/api",
        "//java/google/registry/monitoring/whitebox",
    ],
)
