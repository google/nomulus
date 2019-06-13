package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_js_library", "closure_js_test")

closure_js_library(
    name = "console_test_util",
    srcs = ["console_test_util.js"],
    deps = [
        "//java/google/registry/ui/js",
        "//java/google/registry/ui/js/registrar",
        "//java/google/registry/ui/soy/registrar",
        "@io_bazel_rules_closure//closure/library",
        "@io_bazel_rules_closure//closure/library:testing",
    ],
)

closure_js_test(
    name = "test",
    size = "medium",
    timeout = "short",
    srcs = glob(["*_test.js"]),
    compilation_level = "WHITESPACE_ONLY",
    deps = [
        ":console_test_util",
        "//java/google/registry/ui/js",
        "//java/google/registry/ui/js/registrar",
        "//javatests/google/registry/ui/js:testing",
        "@io_bazel_rules_closure//closure/library",
        "@io_bazel_rules_closure//closure/library:testing",
    ],
)
