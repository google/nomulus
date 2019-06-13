package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_js_library")

closure_js_library(
    name = "registrar",
    srcs = glob(["*.js"]),
    suppress = [
        "JSC_UNKNOWN_EXPR_TYPE",
        "JSC_IMPLICITLY_NULLABLE_JSDOC",
        "JSC_DEPRECATED_PROP_REASON",
        "JSC_STRICT_INEXISTENT_PROPERTY",
        "JSC_REQUIRES_NOT_SORTED",
    ],
    deps = [
        "//java/google/registry/ui/js",
        "//java/google/registry/ui/soy",
        "//java/google/registry/ui/soy/registrar",
        "@io_bazel_rules_closure//closure/library",
    ],
)
