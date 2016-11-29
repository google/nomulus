package(
    default_testonly = 1,
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "TestServer",
    srcs = [
        "HealthzServlet.java",
        "Route.java",
        "ServletWrapperDelegatorServlet.java",
        "StaticResourceServlet.java",
        "TestServer.java",
        "UrlChecker.java",
    ],
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/common/base",
        "//java/com/google/common/collect",
        "//java/com/google/common/net",
        "//java/com/google/common/primitives",
        "//java/com/google/common/util/concurrent",
        "//java/google/registry/util",
        "//third_party/java/auto:auto_value",
        "//third_party/java/jetty/v6_1_22",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/servlet/servlet_api",
    ],
)

java_library(
    name = "Fixture",
    srcs = ["Fixture.java"],
    deps = [
        "//java/com/google/common/collect",
        "//java/google/registry/model",
        "//javatests/google/registry/testing",
        "//third_party/java/appengine:appengine-api",
        "//third_party/java/joda_time",
        "//third_party/java/objectify:objectify-v4_1",
    ],
)

java_library(
    name = "RegistryTestServer",
    srcs = ["RegistryTestServer.java"],
    data = [
        "//java/google/registry/ui:runfiles",
        "//java/google/registry/ui:runfiles_debug",
    ],
    deps = [
        ":TestServer",
        "//java/com/google/common/collect",
        "//java/com/google/common/net",
        "//java/google/registry/module/backend",
        "//java/google/registry/module/frontend",
        "//java/google/registry/ui/server/registrar",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/servlet/servlet_api",
    ],
)

java_binary(
    name = "server",
    srcs = ["RegistryTestServerMain.java"],
    main_class = "google.registry.server.RegistryTestServerMain",
    deps = [
        ":Fixture",
        ":RegistryTestServer",
        "//java/com/google/common/collect",
        "//java/com/google/common/net",
        "//java/google/registry/tools/params",
        "//java/google/registry/ui",
        "//javatests/google/registry/testing",
        "//third_party/java/jcommander",
        "//third_party/java/junit",
    ],
)
