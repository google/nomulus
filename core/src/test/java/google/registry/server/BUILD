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
    ],
    visibility = ["//visibility:public"],
    deps = [
        "//java/google/registry/util",
        "@com_google_appengine_tools_sdk",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@javax_servlet_api",
        "@org_mortbay_jetty",
    ],
)

java_library(
    name = "Fixture",
    srcs = ["Fixture.java"],
    deps = [
        "//java/google/registry/model",
        "//javatests/google/registry/model",
        "//javatests/google/registry/testing",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_guava",
        "@joda_time",
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
        "//java/google/registry/model",
        "//java/google/registry/module/backend",
        "//java/google/registry/module/frontend",
        "//java/google/registry/util",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_guava",
        "@javax_servlet_api",
    ],
)

java_binary(
    name = "server",
    srcs = ["RegistryTestServerMain.java"],
    main_class = "google.registry.server.RegistryTestServerMain",
    deps = [
        ":Fixture",
        ":RegistryTestServer",
        "//java/google/registry/tools/params",
        "//java/google/registry/ui",
        "//javatests/google/registry/testing",
        "@com_beust_jcommander",
        "@com_google_guava",
        "@junit",
    ],
)
