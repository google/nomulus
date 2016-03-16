package(
    default_visibility = ["//java/com/google/domain/registry:registry_project"],
)

java_binary(
    name = "annotation_processors_ide",
    create_executable = False,
    runtime_deps = [
        "@auto_factory//jar",
        "@auto_service//jar",
        "@auto_value//jar",
        "@javawriter//jar",
        "//third_party/java/dagger:dagger-compiler",
    ],
)

java_binary(
    name = "registry_deps",
    create_executable = 0,
    runtime_deps = [
        "//java/com/google/common/testing",
        "//java/com/google/domain/registry/module/frontend",
        "//java/com/google/domain/registry/module/backend",
        "//java/com/google/domain/registry/module/tools",  
        "//java/com/google/domain/registry/tools",
        "//third_party/java/appengine:appengine-api-link",
        "//third_party/java/appengine:appengine-remote-api-link",
        "//third_party/java/appengine:appengine-stubs",
        "//third_party/java/appengine:appengine-integration-testing",
        "//third_party/java/appengine:appengine-testing",
        "//third_party/java/apache_sshd",
        "//third_party/java/ftpserver",
        "//third_party/java/hamcrest",
        "//third_party/java/jetty/v6_1_22",
        "//third_party/java/junit",
        "//third_party/java/mockito",
        "//third_party/java/truth",
    ],
)

# This rule creates a copy of the registry_deps_deploy.jar with all of the
# domain-registry code removed. This is to avoid having duplicate instances
# of domain-registry classes on the eclipse build path.
genrule(
    name = "eclipse_deps",
    srcs = ["//java/com/google/domain/registry/eclipse:registry_deps_deploy.jar"],
    outs = ["eclipse_deps.jar"],
    cmd = " && ".join([
        "IN=$$(pwd)/$(SRCS)",
        "OUT=$$(pwd)/$@",
        "TMP=$$(mktemp -d $${TMPDIR:-/tmp}/eclipse_deps.XXXXXXXX)",
        "cd $$TMP",
        "jar -xf $$IN",
        "rm -rf com/google/domain/registry",
        "jar -cmf META-INF/MANIFEST.MF eclipse_deps.jar .",
        "mv eclipse_deps.jar $$OUT",
        "rm -rf $$TMP",
    ]),
)
