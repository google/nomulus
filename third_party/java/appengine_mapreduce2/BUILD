package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # Apache License 2.0

java_library(
    name = "appengine_mapreduce",
    exports = ["@appengine_mapreduce//jar"],
    runtime_deps = [
        "//third_party/java/appengine_gcs_client",
        "//third_party/java/appengine_pipeline",
        "//third_party/java/charts4j",
        "//third_party/java/joda_time",
        "//third_party/java/servlet/servlet_api",
        "@fastutil//jar",
        "@google_api_client//jar",
        "@google_api_client_appengine//jar",
        "@google_api_services_bigquery//jar",
        "@google_http_client_appengine//jar",
        "@google_http_client_jackson2//jar",
        "@guava//jar",
        "@jackson_core//jar",
        "@jackson_databind//jar",
        "@protobuf_java//jar",
    ],
)
