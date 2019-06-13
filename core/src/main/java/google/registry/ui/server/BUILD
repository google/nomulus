package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "server",
    srcs = glob(["*.java"]),
    resources = [
        "//java/google/registry/ui:globals.txt",
        "//java/google/registry/ui/css:registrar_bin.css.js",
        "//java/google/registry/ui/css:registrar_dbg.css.js",
    ],
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/model",
        "//java/google/registry/ui",
        "//java/google/registry/ui/forms",
        "//java/google/registry/util",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_re2j",
        "@io_bazel_rules_closure//closure/templates",
        "@joda_time",
    ],
)
