package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "model",
    srcs = glob([
        "*.java",
        "*/*.java",
        "*/*/*.java",
    ]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/cache",
        "//java/com/google/common/collect",
        "//java/com/google/common/hash",
        "//java/com/google/common/io",
        "//java/com/google/common/math",
        "//java/com/google/common/net",
        "//java/com/google/common/primitives",
        "//java/com/google/common/reflect",
        "//java/com/google/common/util/concurrent",
        "//java/google/registry/config",
        "//java/google/registry/util",
        "//java/google/registry/xml",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/dagger",
        "//third_party/java/joda_money",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/re2j",
        "//third_party/java/servlet/servlet_api",
    ],
)
