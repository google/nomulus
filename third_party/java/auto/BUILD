package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # Apache License 2.0

java_library(
    name = "auto_common",
    exports = ["@auto_common//jar"],
)

java_library(
    name = "auto_factory",
    exported_plugins = [":auto_factory_plugin"],
    exports = ["@auto_factory//jar"],
)

java_library(
    name = "auto_service",
    exported_plugins = [":auto_service_plugin"],
    exports = ["@auto_service//jar"],
)

java_library(
    name = "auto_value",
    exported_plugins = [":auto_value_plugin"],
    exports = ["@auto_value//jar"],
)

java_plugin(
    name = "auto_factory_plugin",
    processor_class = "com.google.auto.factory.processor.AutoFactoryProcessor",
    visibility = ["//visibility:private"],
    deps = [
        ":auto_common",
        "@auto_factory//jar",
        "@guava//jar",
        "@javawriter//jar",
    ],
)

java_plugin(
    name = "auto_service_plugin",
    processor_class = "com.google.auto.service.processor.AutoServiceProcessor",
    visibility = ["//visibility:private"],
    deps = [
        ":auto_common",
        "@auto_service//jar",
        "@guava//jar",
    ],
)

java_plugin(
    name = "auto_value_plugin",
    processor_class = "com.google.auto.value.processor.AutoValueProcessor",
    visibility = ["//visibility:private"],
    deps = [
        ":auto_common",
        "//third_party/java/jsr305_annotations",
        "@auto_value//jar",
        "@guava//jar",
    ],
)
