package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "groups",
    srcs = glob(["*.java"]),
    deps = [
        "//apiserving/discoverydata/admin:admin_directory_v1",
        "//apiserving/discoverydata/groupssettings",
        "//java/com/google/api/client/googleapis/auth/oauth2",
        "//java/com/google/api/client/googleapis/json",
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/google/registry/config",
        "//java/google/registry/request",
        "//java/google/registry/util",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/servlet/servlet_api",
    ],
)
