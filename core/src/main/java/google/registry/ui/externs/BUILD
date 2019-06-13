package(default_visibility = ["//java/google/registry:registry_project"])

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_js_library")

closure_js_library(
    name = "externs",
    srcs = glob(["*.js"]),
    suppress = [
        "JSC_STRICT_INEXISTENT_PROPERTY",
    ],
)
