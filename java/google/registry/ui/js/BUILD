package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_js_library")

filegroup(
    name = "js_files",
    srcs = glob(["*.js"]),
)

closure_js_library(
    name = "js",
    srcs = [":js_files"],
    deps = [
        "//java/google/registry/ui/soy:Console",
        "//java/google/registry/ui/soy:Forms",
        "//javascript/closure",
    ],
)
