package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "pricing",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/model",
        "//java/google/registry/util",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_dagger",
        "@com_google_guava",
        "@javax_inject",
        "@joda_time",
        "@org_joda_money",
    ],
)
