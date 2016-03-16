licenses(["notice"])  # Apache License 2.0

java_library(
    name = "dagger",
    exported_plugins = [":component-codegen"],
    visibility = ["//visibility:public"],
    exports = [":dagger-runtime"],
)

java_library(
    name = "dagger-runtime",
    exports = [
        "@dagger//jar",
        "//third_party/java/jsr330_inject",
    ],
)

java_library(
    name = "dagger-producers",
    exports = [
        "@dagger_producers//jar",
        "@guava//jar",
        "//third_party/java/jsr330_inject",
    ],
)

java_library(
    name = "dagger-compiler",
    exports = ["@dagger_compiler//jar"],
    visibility = ["//visibility:public"],
    runtime_deps = [
        "@gson//jar",
        "@guava//jar",
        ":dagger-producers",
        ":dagger-runtime",
        "//third_party/java/auto:auto_common",
        "//third_party/java/auto:auto_service",
        "//third_party/java/auto:auto_value",
        "//third_party/java/javapoet",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
    ],
)

java_plugin(
    name = "component-codegen",
    processor_class = "dagger.internal.codegen.ComponentProcessor",
    deps = [":dagger-compiler"],
)
