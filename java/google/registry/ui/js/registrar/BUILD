package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_js_library")

filegroup(
    name = "js_files",
    srcs = glob(["*.js"]),
)

closure_js_library(
    name = "registrar",
    srcs = [":js_files"],
    deps = [
        "//java/google/registry/ui/js",
        "//java/google/registry/ui/soy:Forms",
        "//java/google/registry/ui/soy/registrar:Console",
        "//java/google/registry/ui/soy/registrar:Contact",
        "//java/google/registry/ui/soy/registrar:ContactEpp",
        "//java/google/registry/ui/soy/registrar:ContactSettings",
        "//java/google/registry/ui/soy/registrar:Domain",
        "//java/google/registry/ui/soy/registrar:DomainEpp",
        "//java/google/registry/ui/soy/registrar:Epp",
        "//java/google/registry/ui/soy/registrar:Host",
        "//java/google/registry/ui/soy/registrar:HostEpp",
        "//java/google/registry/ui/soy/registrar:Payment",
        "//java/google/registry/ui/soy/registrar:SecuritySettings",
        "//java/google/registry/ui/soy/registrar:WhoisSettings",
        "//javascript/closure",
    ],
)
