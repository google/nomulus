package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")


java_library(
    name = "clouddns",
    srcs = glob(["*Test.java"]),
    deps = [
        "//apiserving/discoverydata/cloud/dns:cloud_dns_v2beta1_versioned",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/junit",
        "//third_party/java/mockito",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/servlet/servlet_api",
        "//third_party/java/truth",
        "//java/google/registry/dns/writer/clouddns",
        "//java/google/registry/model",
        "//java/google/registry/util",
        "//javatests/google/registry/testing",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [":clouddns"],
)
