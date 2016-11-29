package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "metrics",
    srcs = glob(["*.java"]),
    deps = [
        "//google/monitoring:monitoring_java_lib",
        "//java/com/google/api/client/googleapis/json",
        "//java/com/google/api/client/http",
        "//java/com/google/api/client/json/jackson2",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/util/concurrent",
        "//java/google/registry/monitoring/metrics",
        "//third_party/java/joda_time",
        "//third_party/java/junit",
        "//third_party/java/mockito",
        "//third_party/java/truth",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["*Test.java"]),
    deps = [
        ":metrics",
    ],
)
