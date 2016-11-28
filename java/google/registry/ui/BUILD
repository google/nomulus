package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # Apache 2.0

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_js_binary", "closure_js_deps")
load("//java/google/registry/builddefs:zip_file.bzl", "zip_file")

exports_files(["globals.txt"])


filegroup(
    name = "runfiles",
    srcs = glob(["assets/**"]) + [
        "brain_bin.js",
        "brain_bin_map.js",
        "registrar_bin.js",
        "registrar_bin_map.js",
        "//java/google/registry/ui/css:registrar_bin.css",
        "//java/google/registry/ui/html:html_files",
    ],
)

filegroup(
    name = "runfiles_debug",
    srcs = [
        "@closure_library//:js_files",
        "brain_bin.js.map",
        "deps.js",
        "registrar_bin.js.map",
        "registrar_dbg.js",
        "//javascript/closure:js_files_recursive",
        "//javascript/template/soy:soy_usegoog_js_files",
        "//java/google/registry/ui/css:css_files",
        "//java/google/registry/ui/css:registrar_dbg.css",
        "//java/google/registry/ui/js:js_files",
        "//java/google/registry/ui/js/registrar:js_files",
        "//java/google/registry/ui/soy:js_files",
        "//java/google/registry/ui/soy/registrar:js_files",
    ],
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
    name = "war_debug",
    srcs = [":runfiles_debug"],
    out = "ui_debug.war",
    mappings = {
        "domain_registry/java/google/registry/ui/deps.js": "assets/sources/deps.js",
        "domain_registry/java/google/registry/ui/brain_bin.js.map": "assets/js/brain_bin.js.map",
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
        "//java/com/google/common/base",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/dagger",
        "//third_party/java/jsr305_annotations",
        "//java/google/registry/config",
    ],
)


closure_js_deps(
    name = "deps",
    deps = [
        "//java/google/registry/ui/js",
        "//java/google/registry/ui/js/registrar",
    ],
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
    deps = [
        "//java/google/registry/ui/externs",
        "//java/google/registry/ui/js/registrar",
    ],
)

# The webserver should provide this to trusted admin users, rather than
# registrar_bin.js. This is what makes debugging in production possible.
genrule(
    name = "registrar_mapped",
    srcs = ["registrar_bin.js"],
    outs = ["registrar_bin_map.js"],
    cmd = "cat $(location registrar_bin.js) >$@ && " +
          "echo '//# sourceMappingURL=registrar_bin.js.map' >>$@",
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

################################################################################
## Braintree Payment Method Frame (Brainframe)

closure_js_binary(
    name = "brain_bin",
    entry_points = ["goog:registry.registrar.BrainFrame.main"],
    deps = [
        "//java/google/registry/ui/externs",
        "//java/google/registry/ui/js/registrar",
    ],
)

genrule(
    name = "brain_mapped",
    srcs = ["brain_bin.js"],
    outs = ["brain_bin_map.js"],
    cmd = "cat $(location brain_bin.js) >$@ && " +
          "echo '//# sourceMappingURL=brain_bin.js.map' >>$@",
)
