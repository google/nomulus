package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # Apache License 2.0

java_library(
    name = "appengine_pipeline",
    exports = ["@appengine_pipeline//jar"],
    runtime_deps = [
        "//third_party/java/appengine_gcs_client",
        "//third_party/java/servlet/servlet_api",
        "@guava//jar",
        "@json//jar",
    ],
)
