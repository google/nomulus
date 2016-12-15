package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "rde",
    srcs = glob(["*.java"]),
    resources = glob(["testdata/*"]),
    deps = [
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/google/registry/config",
        "//java/google/registry/gcs",
        "//java/google/registry/keyring/api",
        "//java/google/registry/mapreduce",
        "//java/google/registry/model",
        "//java/google/registry/rde",
        "//java/google/registry/request",
        "//java/google/registry/tldconfig/idn",
        "//java/google/registry/util",
        "//java/google/registry/xjc",
        "//java/google/registry/xml",
        "//javatests/google/registry/testing",
        "//javatests/google/registry/testing/mapreduce",
        "//javatests/google/registry/testing/sftp",
        "//javatests/google/registry/xml",
        "//third_party/java/appengine:appengine-api-testonly",
        "//third_party/java/appengine_gcs_client",
        "//third_party/java/bouncycastle",
        "//third_party/java/bouncycastle_bcpg",
        "//third_party/java/dagger",
        "//third_party/java/ftpserver",
        "//third_party/java/hamcrest",
        "//third_party/java/joda_money",
        "//third_party/java/joda_time",
        "//third_party/java/jsch",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/junit",
        "//third_party/java/mockito",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/servlet/servlet_api",
        "//third_party/java/truth",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    default_test_size = "large",
    test_files = glob(["*Test.java"]),
    deps = [":rde"],
)
