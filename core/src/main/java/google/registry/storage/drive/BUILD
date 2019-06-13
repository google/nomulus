package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "drive",
    srcs = glob(["*.java"]),
    deps = [
        "@com_google_apis_google_api_services_drive",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_guava",
        "@com_google_http_client",
        "@javax_inject",
    ],
)
