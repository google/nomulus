package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0


java_library(
    name = "frontend",
    srcs = glob(["*.java"]),
    deps = [
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/bouncycastle",
        "//third_party/java/dagger",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/servlet/servlet_api",
        "//java/domains/donuts",
        "//java/google/registry/braintree",
        "//java/google/registry/config",
        "//java/google/registry/dns",
        "//java/google/registry/flows",
        "//java/google/registry/keyring/api",
        "//java/google/registry/monitoring/metrics",
        "//java/google/registry/monitoring/whitebox",
        "//java/google/registry/rdap",
        "//java/google/registry/request",
        "//java/google/registry/request:modules",
        "//java/google/registry/ui",
        "//java/google/registry/ui/server/registrar",
        "//java/google/registry/util",
        "//java/google/registry/whois",
    ],
)

# This rule is used so bazel can generate "frontend_jar_deploy.jar" (which
# contains transitive dependencies) for deployment to App Engine. It MUST
# explicitly depend upon upon anything loaded at runtime, e.g. old servlets
# referenced by the module's web.xml file, that isn't statically linked above.
java_binary(
    name = "frontend_jar",
    create_executable = 0,
    runtime_deps = [
        ":frontend",
        "//java/domains/donuts",  # DonutsRegistryConfig
        "//java/google/registry/ui/server/registrar",  # ResourceServlet
    ],
)
