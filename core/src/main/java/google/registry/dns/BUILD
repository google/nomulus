# Routines to publish authoritative DNS.

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
        "//java/google/registry/config",
        "//java/google/registry/dns/writer",
        "//java/google/registry/model",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/request/lock",
        "//java/google/registry/util",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_auto_value",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_monitoring_client_metrics",
        "@com_google_protobuf_java",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
    ],
)
