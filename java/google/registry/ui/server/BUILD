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
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/google/registry/model",
        "//java/google/registry/ui",
        "//java/google/registry/ui/forms",
        "//java/google/registry/util",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/joda_time",
        "//third_party/java/json_simple",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/re2j",
        "//third_party/java/servlet/servlet_api",
        "@io_bazel_rules_closure//closure/templates",
    ],
)
