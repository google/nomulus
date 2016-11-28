package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/com/google/testing/builddefs:GenTestRules.bzl", "GenTestRules")


# Needed for the documentation tests
filegroup(
    name = "flows_files",
    srcs = glob([
        "*.java",
        "**/*.java",
    ]),
)

java_library(
    name = "flows",
    srcs = glob([
        "*.java",
        "**/*.java",
    ]),
    resources = glob(["**/testdata/*.xml"]),
    deps = [
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/com/google/common/testing",
        "//java/domains/donuts",
        "//third_party/java/appengine:appengine-api-testonly",
        "//third_party/java/appengine:appengine-testing",
        "//third_party/java/dagger",
        "//third_party/java/joda_money",
        "//third_party/java/joda_time",
        "//third_party/java/json_simple",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/junit",
        "//third_party/java/mockito",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/re2j",
        "//third_party/java/servlet/servlet_api",
        "//third_party/java/truth",
        "//java/google/registry/config",
        "//java/google/registry/dns",
        "//java/google/registry/flows",
        "//java/google/registry/mapreduce",
        "//java/google/registry/model",
        "//java/google/registry/monitoring/whitebox",
        "//java/google/registry/pricing",
        "//java/google/registry/request",
        "//java/google/registry/tmch",
        "//java/google/registry/util",
        "//java/google/registry/xml",
        "//javatests/google/registry/model",
        "//javatests/google/registry/testing",
        "//javatests/google/registry/testing/mapreduce",
        "//javatests/google/registry/xml",
    ],
)

# If the flows tests should grow again to the point that they last longer than
# sixty seconds, then shard_count should be tuned. You can binary search for a
# good value that balances time reduction with environmental impact. However,
# any unit test that contains fewer @Test methods than the shard count will
# need to be updated to add dummy methods, otherwise blaze will lose its mind.
# If you grep for testNothing you can find the existing dummy methods.
GenTestRules(
    name = "GeneratedTestRules",
    default_test_size = "medium",
    jvm_flags = ["-XX:MaxPermSize=256m"],
    shard_count = 4,
    test_files = glob([
        "*Test.java",
        "*/*Test.java",
    ]),
    deps = [":flows"],
)
