package(
    default_visibility = ["//java/com/google/domain/registry:registry_project"],
)


# Restrict visibility to :tools because :remoteapi-internal (and transitively
# :appengine-api-link) should never be linked into an App Engine deploy jar,
# since the App Engine API is provided by the runtime environment.
package_group(
    name = "allowed-tools",
    packages = [
        "//java/com/google/domain/registry/testing",
        "//java/com/google/domain/registry/tools",
        "//java/com/google/domain/registry/eclipse",
        "//javatests/com/google/domain/registry/tools",
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
        "//java/com/google/domain/registry/bigquery",
        "//java/com/google/domain/registry/config",
        "//java/com/google/domain/registry/export",
        "//java/com/google/domain/registry/flows",
        "//java/com/google/domain/registry/keyring/api",
        "//java/com/google/domain/registry/model",
        "//java/com/google/domain/registry/rde",
        "//java/com/google/domain/registry/security",
        "//java/com/google/domain/registry/request:modules",
        "//java/com/google/domain/registry/tldconfig/idn",
        "//java/com/google/domain/registry/tmch",
        "//java/com/google/domain/registry/tools/params",
        "//java/com/google/domain/registry/tools/server",
        "//java/com/google/domain/registry/tools/soy:soy_java_wrappers",
        "//java/com/google/domain/registry/util",
        "//java/com/google/domain/registry/whois",
        "//java/com/google/domain/registry/xjc",
        "//java/com/google/domain/registry/xml",
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

        "//third_party/java/appengine:appengine-remote-api",

        "//third_party/closure/templates",
    ],
)

java_binary(
    name = "registry_tool",
    create_executable = 1,
    main_class = "com.google.domain.registry.tools.RegistryTool",
    runtime_deps = [
        ":tools",
        "//third_party/java/appengine:appengine-api-link",
        "//third_party/java/appengine:appengine-remote-api-link",
    ],
)

