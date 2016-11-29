package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "clouddns",
    srcs = glob(["*.java"]),
    deps = [
        "//apiserving/discoverydata/cloud/dns:cloud_dns_v2beta1_versioned",
        "//java/com/google/api/client/googleapis/json",
        "//java/com/google/api/client/http",
        "//java/com/google/api/client/json",
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/net",
        "//java/com/google/common/util/concurrent",
        "//java/google/registry/config",
        "//java/google/registry/dns/writer",
        "//java/google/registry/model",
        "//java/google/registry/util",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
    ],
)
