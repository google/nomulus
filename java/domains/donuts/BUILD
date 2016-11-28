package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # Apache 2.0

java_library(
    name = "donuts",
    srcs = glob([
        "config/*.java",
        "flows/*.java",
        "keyring/*.java",
    ]),
    resources = glob([
        "**/*.asc",
    ]),
    deps = [
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/google/registry/config",
        "//java/google/registry/flows",
        "//java/google/registry/keyring/api",
        "//java/google/registry/model",
        "//java/google/registry/pricing",
        "//java/google/registry/request",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/bouncycastle",
        "//third_party/java/bouncycastle_bcpg",
        "//third_party/java/dagger",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/servlet/servlet_api",
    ],
)

load("//java/google/registry/builddefs:zip_file.bzl", "zip_file")
load("//java/google/registry/builddefs:registry_ear_file.bzl", "registry_ear_file")

zip_file(
    name = "common_war",
    srcs = [
        "@appengine_api_sdk//jar",
    ],
    out = "mandatory_stuff.war",
    mappings = {
        "appengine_api_sdk/jar": "WEB-INF/lib",
    },
    visibility = ["//visibility:private"],
)

################################################################################
# DOMAIN REGISTRY :: PRODUCTION ENVIRONMENT

registry_ear_file(
    name = "registry_ear",
    out = "registry.ear",
    configs = {
        "env/common/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default.war": "default",
        "registry_backend.war": "backend",
        "registry_tools.war": "tools",
    },
)

zip_file(
    name = "registry_default_war",
    srcs = [
        "env/common/default/WEB-INF/datastore-indexes.xml",
        "env/common/default/WEB-INF/dos.xml",
        "env/common/default/WEB-INF/logging.properties",
        "env/common/default/WEB-INF/queue.xml",
        "env/common/default/WEB-INF/web.xml",
        "env/production/default/WEB-INF/appengine-web.xml",
        "env/production/default/WEB-INF/cron.xml",
        "//java/domains/donuts/module/frontend:frontend_jar_deploy.jar",
    ],
    out = "registry_default.war",
    mappings = {
        "domain_registry/java/domains/donuts/env/common/default": "",
        "domain_registry/java/domains/donuts/env/production/default": "",
        "domain_registry/java/domains/donuts/module/frontend": "WEB-INF/lib",
    },
    deps = [
        ":common_war",
    ],
)

zip_file(
    name = "registry_backend_war",
    srcs = [
        "env/common/backend/WEB-INF/logging.properties",
        "env/common/backend/WEB-INF/web.xml",
        "env/production/backend/WEB-INF/appengine-web.xml",
        "//java/domains/donuts/module/backend:backend_jar_deploy.jar",
    ],
    out = "registry_backend.war",
    mappings = {
        "domain_registry/java/domains/donuts/env/common/backend": "",
        "domain_registry/java/domains/donuts/env/production/backend": "",
        "domain_registry/java/domains/donuts/module/backend": "WEB-INF/lib",
    },
    deps = [
        ":common_war",
        "//java/google/registry/ui:war",
    ],
)

zip_file(
    name = "registry_tools_war",
    srcs = [
        "env/common/tools/WEB-INF/logging.properties",
        "env/common/tools/WEB-INF/web.xml",
        "env/production/tools/WEB-INF/appengine-web.xml",
        "//java/domains/donuts/module/tools:tools_jar_deploy.jar",
    ],
    out = "registry_tools.war",
    mappings = {
        "domain_registry/java/domains/donuts/env/common/tools": "",
        "domain_registry/java/domains/donuts/env/production/tools": "",
        "domain_registry/java/domains/donuts/module/tools": "WEB-INF/lib",
    },
    deps = [
        ":common_war",
        "//java/google/registry/ui:war",
    ],
)

################################################################################
# DOMAIN REGISTRY :: SANDBOX ENVIRONMENT
#
# Sandbox is a production environment that registrar customers use to conduct
# integration tests against the registry service.

registry_ear_file(
    name = "registry_sandbox_ear",
    out = "registry_sandbox.ear",
    configs = {
        "env/common/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_sandbox.war": "default",
        "registry_backend_sandbox.war": "backend",
        "registry_tools_sandbox.war": "tools",
    },
)

zip_file(
    name = "registry_default_sandbox_war",
    srcs = [
        "env/sandbox/default/WEB-INF/appengine-web.xml",
        "env/sandbox/default/WEB-INF/cron.xml",
    ],
    out = "registry_default_sandbox.war",
    mappings = {"domain_registry/java/domains/donuts/env/sandbox/default": ""},
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_backend_sandbox_war",
    srcs = ["env/sandbox/backend/WEB-INF/appengine-web.xml"],
    out = "registry_backend_sandbox.war",
    mappings = {"domain_registry/java/domains/donuts/env/sandbox/backend": ""},
    deps = [":registry_backend_war"],
)

zip_file(
    name = "registry_tools_sandbox_war",
    srcs = ["env/sandbox/tools/WEB-INF/appengine-web.xml"],
    out = "registry_tools_sandbox.war",
    mappings = {"domain_registry/java/domains/donuts/env/sandbox/tools": ""},
    deps = [":registry_tools_war"],
)

################################################################################
# DOMAIN REGISTRY :: ALPHA ENVIRONMENT
#
# The alpha environment is used by developers to test new features.

registry_ear_file(
    name = "registry_alpha_ear",
    out = "registry_alpha.ear",
    configs = {
        "env/alpha/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_alpha.war": "default",
        "registry_backend_alpha.war": "backend",
        "registry_tools_alpha.war": "tools",
    },
)

zip_file(
    name = "registry_default_alpha_war",
    srcs = [
        "env/alpha/default/WEB-INF/appengine-web.xml",
        "env/alpha/default/WEB-INF/cron.xml",
    ],
    out = "registry_default_alpha.war",
    mappings = {"domain_registry/java/domains/donuts/env/alpha/default": ""},
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_backend_alpha_war",
    srcs = ["env/alpha/backend/WEB-INF/appengine-web.xml"],
    out = "registry_backend_alpha.war",
    mappings = {"domain_registry/java/domains/donuts/env/alpha/backend": ""},
    deps = [":registry_backend_war"],
)

zip_file(
    name = "registry_tools_alpha_war",
    srcs = ["env/alpha/tools/WEB-INF/appengine-web.xml"],
    out = "registry_tools_alpha.war",
    mappings = {"domain_registry/java/domains/donuts/env/alpha/tools": ""},
    deps = [":registry_tools_war"],
)

################################################################################
# DOMAIN REGISTRY :: CRASH ENVIRONMENT
#
# The crash environment is used for testing loads, backups, and restores.

# TODO: determine if META-INF/appengine-application.xml is necessary.
registry_ear_file(
    name = "registry_crash_ear",
    out = "registry_crash.ear",
    configs = {
        "env/crash/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_crash.war": "default",
        "registry_backend_crash.war": "backend",
        "registry_tools_crash.war": "tools",
    },
)

zip_file(
    name = "registry_default_crash_war",
    srcs = [
        "env/crash/default/WEB-INF/appengine-web.xml",
        "env/crash/default/WEB-INF/cron.xml",
    ],
    out = "registry_default_crash.war",
    mappings = {"domain_registry/java/domains/donuts/env/crash/default": ""},
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_backend_crash_war",
    srcs = ["env/crash/backend/WEB-INF/appengine-web.xml"],
    out = "registry_backend_crash.war",
    mappings = {"domain_registry/java/domains/donuts/env/crash/backend": ""},
    deps = [":registry_backend_war"],
)

zip_file(
    name = "registry_tools_crash_war",
    srcs = ["env/crash/tools/WEB-INF/appengine-web.xml"],
    out = "registry_tools_crash.war",
    mappings = {"domain_registry/java/domains/donuts/env/crash/tools": ""},
    deps = [":registry_tools_war"],
)

################################################################################
# DOMAIN REGISTRY :: LOCAL ENVIRONMENT
#
# The local environment only runs locally for testing and is never deployed.

registry_ear_file(
    name = "registry_local_ear",
    out = "registry_local.ear",
    configs = {
        "env/common/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_local.war": "default",
        "registry_backend_local.war": "backend",
        "registry_tools_local.war": "tools",
    },
)

zip_file(
    name = "registry_default_local_war",
    srcs = ["env/local/default/WEB-INF/appengine-web.xml"],
    out = "registry_default_local.war",
    mappings = {"domain_registry/java/domains/donuts/env/local/default": ""},
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_backend_local_war",
    srcs = ["env/local/backend/WEB-INF/appengine-web.xml"],
    out = "registry_backend_local.war",
    mappings = {"domain_registry/java/domains/donuts/env/local/backend": ""},
    deps = [":registry_backend_war"],
)

zip_file(
    name = "registry_tools_local_war",
    srcs = ["env/local/tools/WEB-INF/appengine-web.xml"],
    out = "registry_tools_local.war",
    mappings = {"domain_registry/java/domains/donuts/env/local/tools": ""},
    deps = [":registry_tools_war"],
)
