package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # Apache 2.0, BSD, MIT

java_library(
    name = "appengine-api",
    neverlink = 1,
    exports = ["@appengine_api_sdk//jar"],
)

java_library(
    name = "appengine-api-link",
    exports = ["@appengine_api_sdk//jar"],
)

java_library(
    name = "appengine-api-testonly",
    testonly = 1,
    exports = ["@appengine_api_sdk//jar"],
)

java_library(
    name = "appengine-remote-api",
    neverlink = 1,
    exports = ["@appengine_remote_api//jar"],
)

java_library(
    name = "appengine-remote-api-link",
    exports = ["@appengine_remote_api//jar"],
)

java_library(
    name = "appengine-remote-api-testonly",
    testonly = 1,
    exports = ["@appengine_remote_api//jar"],
)

java_library(
    name = "appengine-api-labs",
    neverlink = 1,
    exports = ["@appengine_api_labs//jar"],
)

java_library(
    name = "appengine-api-labs-link",
    exports = ["@appengine_api_labs//jar"],
)

java_library(
    name = "appengine-api-labs-testonly",
    testonly = 1,
    exports = ["@appengine_api_labs//jar"],
)

java_library(
    name = "appengine-stubs",
    testonly = 1,
    exports = ["@appengine_api_stubs//jar"],
)

java_library(
    name = "appengine-testing",
    testonly = 1,
    exports = [
        ":appengine-api-labs-testonly",
        ":appengine-api-testonly",
        ":appengine-stubs",
        "@appengine_testing//jar",
    ],
)

java_library(
    name = "appengine-integration-testing",
    testonly = 1,
    exports = ["@appengine_tools_sdk//jar"],
)
