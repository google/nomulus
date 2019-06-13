package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "module",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/request",
        "//java/google/registry/util",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_monitoring_client_metrics",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
        "@org_bouncycastle_bcpkix_jdk15on",
    ],
)
