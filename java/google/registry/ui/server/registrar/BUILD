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
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/google/registry/braintree",
        "//java/google/registry/config",
        "//java/google/registry/export/sheet",
        "//java/google/registry/flows",
        "//java/google/registry/model",
        "//java/google/registry/request",
        "//java/google/registry/security",
        "//java/google/registry/ui/forms",
        "//java/google/registry/ui/server",
        "//java/google/registry/ui/soy:soy_java_wrappers",
        "//java/google/registry/ui/soy/registrar:soy_java_wrappers",
        "//java/google/registry/util",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/braintree",
        "//third_party/java/dagger",
        "//third_party/java/joda_money",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/re2j",
        "//third_party/java/servlet/servlet_api",
        "@io_bazel_rules_closure//closure/templates",
    ],
)
