package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "whitebox",
    srcs = glob(["*.java"]),
    deps = [
        "//apiserving/discoverydata/bigquery:bigqueryv2",
        "//google/monitoring:monitoring_java_lib",
        "//java/com/google/api/client/http",
        "//java/com/google/api/client/json",
        "//java/com/google/api/client/util",
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/cache",
        "//java/com/google/common/collect",
        "//java/com/google/common/net",
        "//java/google/registry/bigquery",
        "//java/google/registry/config",
        "//java/google/registry/mapreduce",
        "//java/google/registry/mapreduce/inputs",
        "//java/google/registry/model",
        "//java/google/registry/monitoring/metrics",
        "//java/google/registry/request",
        "//java/google/registry/request:modules",
        "//java/google/registry/util",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/appengine_mapreduce2:appengine_mapreduce",
        "//third_party/java/auto:auto_factory",
        "//third_party/java/auto:auto_value",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/servlet/servlet_api",
    ],
)
