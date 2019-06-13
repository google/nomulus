package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "flows_files",
    srcs = glob([
        "*.java",
        "**/*.java",
    ]),
)

java_library(
    name = "flows",
    srcs = glob([
        "*.java",
        "**/*.java",
    ]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/google/registry/batch",
        "//java/google/registry/config",
        "//java/google/registry/dns",
        "//java/google/registry/model",
        "//java/google/registry/monitoring/whitebox",
        "//java/google/registry/pricing",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/tldconfig/idn",
        "//java/google/registry/tmch",
        "//java/google/registry/util",
        "//java/google/registry/xml",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_monitoring_client_metrics",
        "@com_googlecode_json_simple",
        "@io_bazel_rules_closure//closure/templates",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
        "@org_joda_money",
    ],
)
