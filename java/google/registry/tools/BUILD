package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0


# Restrict visibility to :tools because :remoteapi-internal (and transitively
# :appengine-api-link) should never be linked into an App Engine deploy jar,
# since the App Engine API is provided by the runtime environment.
package_group(
    name = "allowed-tools",
    packages = [
        "//java/google/registry/eclipse",
        "//java/google/registry/testing",
        "//java/google/registry/tools",
        "//javatests/google/registry/tools",
    ],
)

java_library(
    name = "tools",
    srcs = glob([
        "*.java",
        "javascrap/*.java",
    ]),
    resources = glob([
        "*.properties",
        "sql/*.sql",
    ]),
    visibility = [":allowed-tools"],
    deps = [
        "//apiserving/discoverydata/bigquery:bigqueryv2",
        "//java/com/google/api/client/googleapis/auth/oauth2",
        "//java/com/google/api/client/http",
        "//java/com/google/api/client/http/javanet",
        "//java/com/google/api/client/json",
        "//java/com/google/api/client/json/jackson2",
        "//java/com/google/common/annotations",
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/hash",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/com/google/common/util/concurrent",
        "//java/domains/donuts/keyring",
        "//java/google/registry/bigquery",
        "//java/google/registry/config",
        "//java/google/registry/dns/writer",
        "//java/google/registry/dns/writer/clouddns",
        "//java/google/registry/dns/writer/dnsupdate",
        "//java/google/registry/export",
        "//java/google/registry/flows",
        "//java/google/registry/keyring/api",
        "//java/google/registry/model",
        "//java/google/registry/pricing",
        "//java/google/registry/rde",
        "//java/google/registry/security",
        "//java/google/registry/request:modules",
        "//java/google/registry/tldconfig/idn",
        "//java/google/registry/tmch",
        "//java/google/registry/tools/params",
        "//java/google/registry/tools/server",
        "//java/google/registry/tools/soy:soy_java_wrappers",
        "//java/google/registry/util",
        "//java/google/registry/whois",
        "//java/google/registry/xjc",
        "//java/google/registry/xml",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/bouncycastle",
        "//third_party/java/bouncycastle_bcpg",
        "//third_party/java/dagger",
        "//third_party/java/jcommander",
        "//third_party/java/joda_money",
        "//third_party/java/joda_time",
        "//third_party/java/json",
        "//third_party/java/json_simple",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/objectify:objectify-v4_1",
        "//third_party/java/re2j",

        "//third_party/java/appengine:appengine-remote-api",

        "@io_bazel_rules_closure//closure/templates",
    ],
)

java_binary(
    name = "nomulus",
    create_executable = 1,
    main_class = "google.registry.tools.RegistryTool",
    runtime_deps = [
        ":tools",
        "//third_party/java/appengine:appengine-api-link",
        "//third_party/java/appengine:appengine-remote-api-link",
    ],
)

