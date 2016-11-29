package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "api",
    srcs = glob(["*.java"]),
    resources = glob(["*.asc"]),
    deps = [
        "//java/com/google/common/base",
        "//java/com/google/common/io",
        "//third_party/java/bouncycastle",
        "//third_party/java/bouncycastle_bcpg",
        "//third_party/java/dagger",
        "//third_party/java/jsr305_annotations",
    ],
)
