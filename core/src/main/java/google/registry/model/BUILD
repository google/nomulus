package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "model",
    srcs = glob([
        "*.java",
        "**/*.java",
    ]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/util",
        "//java/google/registry/xml",
        "//third_party/jaxb",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_monitoring_client_metrics",
        "@com_google_re2j",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
        "@org_joda_money",
    ],
)
