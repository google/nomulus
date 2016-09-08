package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0


java_library(
    name = "backend",
    srcs = glob(["*.java"]),
    deps = [
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/net",
        "//java/domains/donuts/keyring",
        "//third_party/java/bouncycastle",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/servlet/servlet_api",
        "//java/google/registry/backup",
        "//java/google/registry/bigquery",
        "//java/google/registry/billing",
        "//java/google/registry/config",
        "//java/google/registry/cron",
        "//java/google/registry/dns",
        "//java/google/registry/dns/writer/dnsupdate",
        "//java/google/registry/export",
        "//java/google/registry/export/sheet",
        "//java/google/registry/flows",
        "//java/google/registry/gcs",
        "//java/google/registry/groups",
        "//java/google/registry/keyring/api",
        "//java/google/registry/mapreduce",
        "//java/google/registry/model",
        "//java/google/registry/monitoring/metrics",
        "//java/google/registry/monitoring/whitebox",
        "//java/google/registry/rde",
        "//java/google/registry/request",
        "//java/google/registry/request:modules",
        "//java/google/registry/tmch",
        "//java/google/registry/util",
    ],
)

# This rule is used so bazel can generate "backend_jar_deploy.jar" (which
# contains transitive dependencies) for deployment to App Engine. It MUST
# explicitly depend upon upon anything loaded at runtime, e.g. old servlets
# referenced by the module's web.xml file, that isn't statically linked above.
java_binary(
    name = "backend_jar",
    create_executable = 0,
    runtime_deps = [
        ":backend",
        "//java/domains/donuts/config",  # DonutsRegistryConfig
        "//java/google/registry/monitoring/whitebox",  # MetricsTaskServlet
    ],
)
