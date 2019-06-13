package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "params",
    srcs = glob(["*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/google/registry/model",
        "//java/google/registry/util",
        "@com_beust_jcommander",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
        "@com_google_re2j",
        "@joda_time",
        "@org_joda_money",
    ],
)
