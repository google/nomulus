package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_java_template_library", "closure_js_template_library")

filegroup(
    name = "js_files",
    srcs = [
        "Console.soy.js",
        "Contact.soy.js",
        "ContactEpp.soy.js",
        "ContactSettings.soy.js",
        "Domain.soy.js",
        "DomainEpp.soy.js",
        "Epp.soy.js",
        "Host.soy.js",
        "HostEpp.soy.js",
        "Payment.soy.js",
        "SecuritySettings.soy.js",
        "WhoisSettings.soy.js",
    ],
)

closure_java_template_library(
    name = "soy_java_wrappers",
    srcs = glob(["*.soy"]),
    java_package = "google.registry.ui.soy.registrar",
)

closure_js_template_library(
    name = "Console",
    srcs = ["Console.soy"],
    globals = "//java/google/registry/ui:globals.txt",
    deps = ["//java/google/registry/ui/soy:Console"],
)

closure_js_template_library(
    name = "ContactEpp",
    srcs = ["ContactEpp.soy"],
    globals = "//java/google/registry/ui:globals.txt",
)

closure_js_template_library(
    name = "ContactSettings",
    srcs = ["ContactSettings.soy"],
    globals = "//java/google/registry/ui:globals.txt",
    deps = ["//java/google/registry/ui/soy:Forms"],
)

closure_js_template_library(
    name = "Contact",
    srcs = ["Contact.soy"],
    globals = "//java/google/registry/ui:globals.txt",
    deps = ["//java/google/registry/ui/soy:Forms"],
)

closure_js_template_library(
    name = "Domain",
    srcs = ["Domain.soy"],
    globals = "//java/google/registry/ui:globals.txt",
    deps = ["//java/google/registry/ui/soy:Forms"],
)

closure_js_template_library(
    name = "DomainEpp",
    srcs = ["DomainEpp.soy"],
    globals = "//java/google/registry/ui:globals.txt",
)

closure_js_template_library(
    name = "Epp",
    srcs = ["Epp.soy"],
    globals = "//java/google/registry/ui:globals.txt",
)

closure_js_template_library(
    name = "HostEpp",
    srcs = ["HostEpp.soy"],
    globals = "//java/google/registry/ui:globals.txt",
)

closure_js_template_library(
    name = "Host",
    srcs = ["Host.soy"],
    globals = "//java/google/registry/ui:globals.txt",
    deps = ["//java/google/registry/ui/soy:Forms"],
)

closure_js_template_library(
    name = "Payment",
    srcs = ["Payment.soy"],
    globals = "//java/google/registry/ui:globals.txt",
    deps = ["//java/google/registry/ui/soy:Forms"],
)

closure_js_template_library(
    name = "SecuritySettings",
    srcs = ["SecuritySettings.soy"],
    globals = "//java/google/registry/ui:globals.txt",
)

closure_js_template_library(
    name = "WhoisSettings",
    srcs = ["WhoisSettings.soy"],
    globals = "//java/google/registry/ui:globals.txt",
    deps = ["//java/google/registry/ui/soy:Forms"],
)
