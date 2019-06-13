# Description:
#   This package contains the code for the binary that proxies TCP traffic from
#   the GCE/GKE to AppEngine.

load("@io_bazel_rules_docker//container:container.bzl", "container_image", "container_push")

package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "proxy",
    srcs = glob(["**/*.java"]),
    resources = glob([
        "resources/*",
        "config/*.yaml",
    ]),
    deps = [
        "//java/google/registry/util",
        "@com_beust_jcommander",
        "@com_google_api_client",
        "@com_google_apis_google_api_services_cloudkms",
        "@com_google_apis_google_api_services_monitoring",
        "@com_google_apis_google_api_services_storage",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_gson",
        "@com_google_guava",
        "@com_google_monitoring_client_metrics",
        "@com_google_monitoring_client_stackdriver",
        "@io_netty_buffer",
        "@io_netty_codec",
        "@io_netty_codec_http",
        "@io_netty_common",
        "@io_netty_handler",
        "@io_netty_transport",
        "@javax_inject",
        "@joda_time",
        "@org_bouncycastle_bcpkix_jdk15on",
    ],
)

java_binary(
    name = "proxy_server",
    main_class = "google.registry.proxy.ProxyServer",
    runtime_deps = [
        ":proxy",
        "@io_netty_tcnative",
    ],
)

container_image(
    name = "proxy_image",
    base = "@java_base//image",
    entrypoint = [
        "java",
        "-jar",
        "proxy_server_deploy.jar",
    ],
    files = [":proxy_server_deploy.jar"],
    ports = [
        "30000",
        "30001",
        "30002",
        "30010",
        "30011",
    ],
)

container_push(
    name = "proxy_push",
    format = "Docker",
    image = ":proxy_image",
    registry = "gcr.io",
    repository = "GCP_PROJECT/IMAGE_NAME",
)
