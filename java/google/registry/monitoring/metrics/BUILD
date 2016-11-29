package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "metrics",
    srcs = glob(["*.java"]),
    deps = [
        "//google/monitoring:monitoring_java_lib",
        "//java/com/google/api/client/googleapis/json",
        "//java/com/google/api/client/http",
        "//java/com/google/api/client/json",
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/html",
        "//java/com/google/common/math",
        "//java/com/google/common/primitives",
        "//java/com/google/common/util/concurrent",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/auto:auto_value",
        "//third_party/java/dagger",
        "//third_party/java/error_prone:annotations",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/re2j",
    ],
)
