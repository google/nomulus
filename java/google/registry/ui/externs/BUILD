package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_js_library")

closure_js_library(
    name = "externs",
    externs = glob(["*.js"]),
)

filegroup(
    name = "js_files_recursive",
    srcs = glob(["*.js"]),
)
