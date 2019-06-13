package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "tmch",
    srcs = glob(["*.java"]),
    resources = glob([
        "*.crl",
        "*.crt",
        "*.asc",
    ]),
    deps = [
        "//java/google/registry/config",
        "//java/google/registry/keyring/api",
        "//java/google/registry/model",
        "//java/google/registry/request",
        "//java/google/registry/request/auth",
        "//java/google/registry/util",
        "//java/google/registry/xml",
        "//third_party/objectify:objectify-v4_1",
        "@com_google_appengine_api_1_0_sdk",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@com_google_re2j",
        "@javax_inject",
        "@javax_servlet_api",
        "@joda_time",
        "@org_bouncycastle_bcpg_jdk15on",
        "@org_bouncycastle_bcpkix_jdk15on",
    ],
)
