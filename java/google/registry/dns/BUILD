# Description:
#   Routines to publish authoritative DNS.

package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "constants",
    srcs = ["DnsConstants.java"],
)

java_library(
    name = "dns",
    srcs = glob(
        ["*.java"],
        exclude = ["DnsConstants.java"],
    ),
    deps = [
        ":constants",
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/html",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/google/registry/config",
        "//java/google/registry/dns/writer",
        "//java/google/registry/model",
        "//java/google/registry/monitoring/metrics",
        "//java/google/registry/request",
        "//java/google/registry/util",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/auto:auto_value",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/servlet/servlet_api",
    ],
)
