package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "registrar",
    srcs = glob(["*.java"]),
    resources = [
        "//java/google/registry/ui/css:registrar_bin.css.js",
        "//java/google/registry/ui/css:registrar_dbg.css.js",
    ],
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/export/sheet",
        "//java/google/registry/flows",
        "//java/google/registry/model",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/security",
        "//java/google/registry/ui/forms",
        "//java/google/registry/ui/server",
        "//java/google/registry/ui/soy:soy_java_wrappers",
        "//java/google/registry/ui/soy/registrar:soy_java_wrappers",
        "//java/google/registry/util",
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
        "@io_bazel_rules_closure//closure/templates",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
        "@org_joda_money",
    ],
)
