package(default_visibility = ["//java/google/registry:registry_project"])

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_css_binary", "closure_css_library")

closure_css_library(
    name = "kd_components_lib",
    srcs = ["kd_components.css"],
)

closure_css_library(
    name = "registry_lib",
    srcs = [
        "console.css",
        "forms.css",
        "registry.css",
    ],
    deps = [
        ":kd_components_lib",
    ],
)

closure_css_library(
    name = "registrar_lib",
    srcs = [
        "admin-settings.css",
        "contact-settings.css",
        "contact-us.css",
        "dashboard.css",
        "epp.css",
        "resources.css",
        "security-settings.css",
    ],
    deps = [":registry_lib"],
)

closure_css_binary(
    name = "registrar_bin",
    deps = [":registrar_lib"],
)

closure_css_binary(
    name = "registrar_dbg",
    debug = 1,
    deps = [":registrar_lib"],
)

closure_css_library(
    name = "registrar_raw",
    srcs = ["registrar_imports_raw.css"],
    deps = [":registrar_lib"],
)
