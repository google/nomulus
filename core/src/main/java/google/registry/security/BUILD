package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "security",
    srcs = glob(
        ["*.java"],
        exclude = glob(["*Servlet.java"]),
    ),
    deps = [
        "//java/google/registry/model",
        "//java/google/registry/util",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_code_findbugs_jsr305",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_googlecode_json_simple",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
    ],
)
