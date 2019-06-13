package(default_visibility = ["//java/google/registry:registry_project"])

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_js_binary", "closure_js_deps")
load("//java/google/registry/builddefs:zip_file.bzl", "zip_file")

exports_files(["globals.txt"])

filegroup(
    name = "runfiles",
    srcs = glob(["assets/**"]) + [
        "registrar_bin.js",
        "//java/google/registry/ui/css:registrar_bin.css",
        "//java/google/registry/ui/html:html_files",
    ],
)

filegroup(
    name = "minimal_runfiles",
    srcs = [
        "//java/google/registry/ui/html:html_files",
    ],
)

filegroup(
    name = "runfiles_debug",
    srcs = [
        ":deps",
        ":registrar_bin",
        ":registrar_dbg",
        "//java/google/registry/ui/css:registrar_dbg",
    ],
    data = [":deps"],
)

zip_file(
    name = "war",
    srcs = [":runfiles"],
    out = "ui.war",
    mappings = {
        "domain_registry/java/google/registry/ui/assets": "assets",
        "domain_registry/java/google/registry/ui/css": "assets/css",
        "domain_registry/java/google/registry/ui/html": "",
        "domain_registry/java/google/registry/ui": "assets/js",
    },
)

zip_file(
    name = "minimal_war",
    srcs = [":minimal_runfiles"],
    out = "minimal_ui.war",
    mappings = {
        "domain_registry/java/google/registry/ui/html": "",
    },
)

zip_file(
    name = "war_debug",
    srcs = [":runfiles_debug"],
    out = "ui_debug.war",
    data = [":runfiles_debug"],
    mappings = {
        "domain_registry/java/google/registry/ui/registrar_bin.js.map": "assets/js/registrar_bin.js.map",
        "domain_registry/java/google/registry/ui/registrar_dbg.js": "assets/js/registrar_dbg.js",
        "domain_registry/java/google/registry/ui/css/registrar_dbg.css": "assets/css/registrar_dbg.css",
        "": "assets/sources",
    },
    deps = [":war"],
)

java_library(
    name = "ui",
    srcs = glob(["*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
    ],
)

closure_js_deps(
    name = "deps",
    deps = ["//java/google/registry/ui/js/registrar"],
)

################################################################################
## Registrar Console

# This is the full-blown compiled JavaScript source code for the registrar
# console. Everything, including the soy templates, is compiled into a single
# .js file. The only symbols that will be available are the ones you @export.
# This will also replace calls to goog.getCssName() and {css ...} with their
# non-union minified equivalents.
closure_js_binary(
    name = "registrar_bin",
    css = "//java/google/registry/ui/css:registrar_bin",
    entry_points = ["goog:registry.registrar.main"],
    output_wrapper = "%output%//# sourceMappingURL=registrar_bin.js.map",
    deps = [
        "//java/google/registry/ui/externs",
        "//java/google/registry/ui/js/registrar",
    ],
)

# This target creates a compiled JavaScript file where symbols are renamed to
# include dollar signs. This is useful for testing, because you can still read
# the source code, but it'll fail if your code is incorrect with regard to
# dotted and quoted properties. The same applies to CSS class names, which get
# an extra underscore.
closure_js_binary(
    name = "registrar_dbg",
    css = "//java/google/registry/ui/css:registrar_dbg",
    debug = 1,
    entry_points = ["goog:registry.registrar.main"],
    formatting = "PRETTY_PRINT",
    deps = [
        "//java/google/registry/ui/externs",
        "//java/google/registry/ui/js/registrar",
    ],
)
