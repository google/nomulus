package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "bigquery",
    srcs = glob(["*.java"]),
    deps = [
        "//apiserving/discoverydata/bigquery:bigqueryv2",
        "//java/com/google/api/client/extensions/appengine/http",
        "//java/com/google/api/client/googleapis/auth/oauth2",
        "//java/com/google/api/client/googleapis/extensions/appengine/auth/oauth2",
        "//java/com/google/api/client/googleapis/json",
        "//java/com/google/api/client/http",
        "//java/com/google/api/client/json",
        "//java/com/google/api/client/json/jackson2",
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/io",
        "//java/com/google/common/util/concurrent",
        "//java/google/registry/config",
        "//java/google/registry/request",
        "//java/google/registry/util",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
    ],
)
