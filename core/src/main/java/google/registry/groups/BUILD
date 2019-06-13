package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "groups",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/util",
        "@com_google_api_client",
        "@com_google_apis_google_api_services_admin_directory",
        "@com_google_apis_google_api_services_groupssettings",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@javax_inject",
        "@javax_servlet_api",
    ],
)
