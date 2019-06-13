package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "loadtest",
    srcs = glob(["*.java"]),
    resources = glob(["templates/*.xml"]),
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/security",
        "//java/google/registry/util",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
    ],
)
