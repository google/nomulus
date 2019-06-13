package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "dnsupdate",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/dns/writer",
        "//java/google/registry/model",
        "//java/google/registry/util",
        "@com_google_dagger",
        "@com_google_guava",
        "@dnsjava",
        "@javax_inject",
        "@joda_time",
    ],
)
