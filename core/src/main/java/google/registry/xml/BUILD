package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "xml_schema_files",
    srcs = glob(["xsd/*.xsd"]),
)

java_library(
    name = "xml",
    srcs = glob(["*.java"]),
    resources = [":xml_schema_files"],
    deps = [
        "//third_party/jaxb",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
        "@com_google_re2j",
        "@joda_time",
    ],
)
