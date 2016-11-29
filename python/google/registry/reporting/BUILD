package(default_visibility = ["//java/google/registry:registry_project"])

licenses(["notice"])  # Apache 2.0

py_library(
    name = "icann_report_query_builder",
    srcs = ["icann_report_query_builder.py"],
    deps = ["//python:python_directory_import"],
)

py_test(
    name = "icann_report_query_builder_test",
    size = "small",
    srcs = ["icann_report_query_builder_test.py"],
    data = ["testdata/golden_activity_query.sql"],
    deps = [":icann_report_query_builder"],
)
