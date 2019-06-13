package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/google/registry/builddefs:zip_file.bzl", "zip_file")
load("//java/google/registry/builddefs:registry_ear_file.bzl", "registry_ear_file")

package_group(
    name = "registry_project",
    packages = [
        "//java/google/registry/...",
        "//javatests/google/registry/...",
        "//python/...",
    ],
)

zip_file(
    name = "common_war",
    srcs = [
        "@com_google_appengine_api_1_0_sdk",
    ],
    out = "mandatory_stuff.war",
    mappings = {
        "com_google_appengine_api_1_0_sdk": "WEB-INF/lib",
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
        "registry_pubapi.war": "pubapi",
        "registry_backend.war": "backend",
        "registry_tools.war": "tools",
    },
)

# We use the production "nocron" earfile only in the event of a datastore
# restore.
# for details.
registry_ear_file(
    name = "registry_nocron_ear",
    out = "registry_nocron.ear",
    configs = {
        "env/common/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_nocron.war": "default",
        "registry_pubapi.war": "pubapi",
        "registry_backend.war": "backend",
        "registry_tools.war": "tools",
    },
)

zip_file(
    name = "registry_default_war",
    srcs = [
        "env/common/default/WEB-INF/datastore-indexes.xml",
        "env/common/default/WEB-INF/dispatch.xml",
        "env/common/default/WEB-INF/dos.xml",
        "env/common/default/WEB-INF/logging.properties",
        "env/common/default/WEB-INF/queue.xml",
        "env/common/default/WEB-INF/web.xml",
        "env/production/default/WEB-INF/appengine-web.xml",
        "env/production/default/WEB-INF/cron.xml",
        "//java/google/registry/module/frontend:frontend_jar_deploy.jar",
    ],
    out = "registry_default.war",
    mappings = {
        "domain_registry/java/google/registry/env/common/default": "",
        "domain_registry/java/google/registry/env/production/default": "",
        "domain_registry/java/google/registry/module/frontend": "WEB-INF/lib",
    },
    deps = [
        ":common_war",
        "//java/google/registry/ui:war_debug",
    ],
)

zip_file(
    name = "registry_default_nocron_war",
    out = "registry_default_nocron.war",
    exclude = ["WEB-INF/cron.xml"],
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_pubapi_war",
    srcs = [
        "env/common/pubapi/WEB-INF/dos.xml",
        "env/common/pubapi/WEB-INF/logging.properties",
        "env/common/pubapi/WEB-INF/web.xml",
        "env/production/pubapi/WEB-INF/appengine-web.xml",
        "//java/google/registry/module/pubapi:pubapi_jar_deploy.jar",
    ],
    out = "registry_pubapi.war",
    mappings = {
        "domain_registry/java/google/registry/env/common/pubapi": "",
        "domain_registry/java/google/registry/env/production/pubapi": "",
        "domain_registry/java/google/registry/module/pubapi": "WEB-INF/lib",
    },
    deps = [
        ":common_war",
        "//java/google/registry/ui:war_debug",
    ],
)

zip_file(
    name = "registry_backend_war",
    srcs = [
        "env/common/backend/WEB-INF/logging.properties",
        "env/common/backend/WEB-INF/web.xml",
        "env/production/backend/WEB-INF/appengine-web.xml",
        "//java/google/registry/module/backend:backend_jar_deploy.jar",
    ],
    out = "registry_backend.war",
    mappings = {
        "domain_registry/java/google/registry/env/common/backend": "",
        "domain_registry/java/google/registry/env/production/backend": "",
        "domain_registry/java/google/registry/module/backend": "WEB-INF/lib",
    },
    deps = [
        ":common_war",
        "//java/google/registry/ui:minimal_war",
    ],
)

zip_file(
    name = "registry_tools_war",
    srcs = [
        "env/common/tools/WEB-INF/logging.properties",
        "env/common/tools/WEB-INF/web.xml",
        "env/production/tools/WEB-INF/appengine-web.xml",
        "//java/google/registry/module/tools:tools_jar_deploy.jar",
    ],
    out = "registry_tools.war",
    mappings = {
        "domain_registry/java/google/registry/env/common/tools": "",
        "domain_registry/java/google/registry/env/production/tools": "",
        "domain_registry/java/google/registry/module/tools": "WEB-INF/lib",
    },
    deps = [
        ":common_war",
        "//java/google/registry/ui:war",
    ],
)

################################################################################
# DOMAIN REGISTRY :: QUALITY ASSURANCE ENVIRONMENT
#
# QA is an environment for automated integration testing and manual acceptance testing.

registry_ear_file(
    name = "registry_qa_ear",
    out = "registry_qa.ear",
    configs = {
        "env/common/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_qa.war": "default",
        "registry_pubapi_qa.war": "pubapi",
        "registry_backend_qa.war": "backend",
        "registry_tools_qa.war": "tools",
    },
)

zip_file(
    name = "registry_default_qa_war",
    srcs = [
        "env/qa/default/WEB-INF/appengine-web.xml",
        "env/qa/default/WEB-INF/cron.xml",
    ],
    out = "registry_default_qa.war",
    mappings = {
        "domain_registry/java/google/registry/env/qa/default": "",
    },
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_pubapi_qa_war",
    srcs = [
        "env/qa/pubapi/WEB-INF/appengine-web.xml",
    ],
    out = "registry_pubapi_qa.war",
    mappings = {
        "domain_registry/java/google/registry/env/qa/pubapi": "",
    },
    deps = [":registry_pubapi_war"],
)

zip_file(
    name = "registry_backend_qa_war",
    srcs = [
        "env/qa/backend/WEB-INF/appengine-web.xml",
    ],
    out = "registry_backend_qa.war",
    mappings = {
        "domain_registry/java/google/registry/env/qa/backend": "",
    },
    deps = [":registry_backend_war"],
)

zip_file(
    name = "registry_tools_qa_war",
    srcs = [
        "env/qa/tools/WEB-INF/appengine-web.xml",
    ],
    out = "registry_tools_qa.war",
    mappings = {
        "domain_registry/java/google/registry/env/qa/tools": "",
    },
    deps = [":registry_tools_war"],
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
        "registry_pubapi_sandbox.war": "pubapi",
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
    mappings = {
        "domain_registry/java/google/registry/env/sandbox/default": "",
    },
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_pubapi_sandbox_war",
    srcs = [
        "env/sandbox/pubapi/WEB-INF/appengine-web.xml",
    ],
    out = "registry_pubapi_sandbox.war",
    mappings = {
        "domain_registry/java/google/registry/env/sandbox/pubapi": "",
    },
    deps = [":registry_pubapi_war"],
)

zip_file(
    name = "registry_backend_sandbox_war",
    srcs = [
        "env/sandbox/backend/WEB-INF/appengine-web.xml",
    ],
    out = "registry_backend_sandbox.war",
    mappings = {
        "domain_registry/java/google/registry/env/sandbox/backend": "",
    },
    deps = [":registry_backend_war"],
)

zip_file(
    name = "registry_tools_sandbox_war",
    srcs = [
        "env/sandbox/tools/WEB-INF/appengine-web.xml",
    ],
    out = "registry_tools_sandbox.war",
    mappings = {
        "domain_registry/java/google/registry/env/sandbox/tools": "",
    },
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
        "env/common/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_alpha.war": "default",
        "registry_pubapi_alpha.war": "pubapi",
        "registry_backend_alpha.war": "backend",
        "registry_tools_alpha.war": "tools",
    },
)

# The "nocron" files are, unsurprisingly, versions of the archives that have
# had cron.xml removed.  We do this because it's necessary to deploy them in
# this way when restoring a backup.
#
# "nocron" archives are currently prepared for production, alpha and crash. To
# prepare them for another environment, just do something similar.
registry_ear_file(
    name = "registry_alpha_nocron_ear",
    out = "registry_alpha_nocron.ear",
    configs = {
        "env/common/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_alpha_nocron.war": "default",
        "registry_pubapi_alpha.war": "pubapi",
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
    mappings = {
        "domain_registry/java/google/registry/env/alpha/default": "",
    },
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_default_alpha_nocron_war",
    out = "registry_default_alpha_nocron.war",
    exclude = ["WEB-INF/cron.xml"],
    deps = [":registry_default_alpha_war"],
)

zip_file(
    name = "registry_pubapi_alpha_war",
    srcs = [
        "env/alpha/pubapi/WEB-INF/appengine-web.xml",
    ],
    out = "registry_pubapi_alpha.war",
    mappings = {
        "domain_registry/java/google/registry/env/alpha/pubapi": "",
    },
    deps = [":registry_pubapi_war"],
)

zip_file(
    name = "registry_backend_alpha_war",
    srcs = [
        "env/alpha/backend/WEB-INF/appengine-web.xml",
    ],
    out = "registry_backend_alpha.war",
    mappings = {
        "domain_registry/java/google/registry/env/alpha/backend": "",
    },
    deps = [":registry_backend_war"],
)

zip_file(
    name = "registry_tools_alpha_war",
    srcs = [
        "env/alpha/tools/WEB-INF/appengine-web.xml",
    ],
    out = "registry_tools_alpha.war",
    mappings = {
        "domain_registry/java/google/registry/env/alpha/tools": "",
    },
    deps = [":registry_tools_war"],
)

################################################################################
# DOMAIN REGISTRY :: CRASH ENVIRONMENT
#
# The crash environment is used for testing loads, backups, and restores.

registry_ear_file(
    name = "registry_crash_ear",
    out = "registry_crash.ear",
    configs = {
        "env/common/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_crash.war": "default",
        "registry_pubapi_crash.war": "pubapi",
        "registry_backend_crash.war": "backend",
        "registry_tools_crash.war": "tools",
    },
)

# The "nocron" files are, unsurprisingly, versions of the archives that have
# had cron.xml removed.  We do this because it's necessary to deploy them in
# this way when restoring a backup.
#
# "nocron" archives are currently prepared for production, alpha and crash. To
# prepare them for another environment, just do something similar.
registry_ear_file(
    name = "registry_crash_nocron_ear",
    out = "registry_crash_nocron.ear",
    configs = {
        "env/common/META-INF/appengine-application.xml": "META-INF/appengine-application.xml",
        "env/common/META-INF/application.xml": "META-INF/application.xml",
    },
    wars = {
        "registry_default_crash_nocron.war": "default",
        "registry_pubapi_crash.war": "pubapi",
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
    mappings = {
        "domain_registry/java/google/registry/env/crash/default": "",
    },
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_default_crash_nocron_war",
    out = "registry_default_crash_nocron.war",
    exclude = ["WEB-INF/cron.xml"],
    deps = [":registry_default_crash_war"],
)

zip_file(
    name = "registry_pubapi_crash_war",
    srcs = [
        "env/crash/pubapi/WEB-INF/appengine-web.xml",
    ],
    out = "registry_pubapi_crash.war",
    mappings = {
        "domain_registry/java/google/registry/env/crash/pubapi": "",
    },
    deps = [":registry_pubapi_war"],
)

zip_file(
    name = "registry_backend_crash_war",
    srcs = [
        "env/crash/backend/WEB-INF/appengine-web.xml",
    ],
    out = "registry_backend_crash.war",
    mappings = {
        "domain_registry/java/google/registry/env/crash/backend": "",
    },
    deps = [":registry_backend_war"],
)

zip_file(
    name = "registry_tools_crash_war",
    srcs = [
        "env/crash/tools/WEB-INF/appengine-web.xml",
    ],
    out = "registry_tools_crash.war",
    mappings = {
        "domain_registry/java/google/registry/env/crash/tools": "",
    },
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
        "registry_pubapi_local.war": "pubapi",
        "registry_backend_local.war": "backend",
        "registry_tools_local.war": "tools",
    },
)

zip_file(
    name = "registry_default_local_war",
    srcs = [
        "env/local/default/WEB-INF/appengine-web.xml",
    ],
    out = "registry_default_local.war",
    mappings = {
        "domain_registry/java/google/registry/env/local/default": "",
    },
    deps = [":registry_default_war"],
)

zip_file(
    name = "registry_pubapi_local_war",
    srcs = [
        "env/local/pubapi/WEB-INF/appengine-web.xml",
    ],
    out = "registry_pubapi_local.war",
    mappings = {
        "domain_registry/java/google/registry/env/local/pubapi": "",
    },
    deps = [":registry_pubapi_war"],
)

zip_file(
    name = "registry_backend_local_war",
    srcs = [
        "env/local/backend/WEB-INF/appengine-web.xml",
    ],
    out = "registry_backend_local.war",
    mappings = {
        "domain_registry/java/google/registry/env/local/backend": "",
    },
    deps = [":registry_backend_war"],
)

zip_file(
    name = "registry_tools_local_war",
    srcs = [
        "env/local/tools/WEB-INF/appengine-web.xml",
    ],
    out = "registry_tools_local.war",
    mappings = {
        "domain_registry/java/google/registry/env/local/tools": "",
    },
    deps = [":registry_tools_war"],
)
