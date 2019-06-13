package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "api",
    srcs = glob(["*.java"]),
    resources = glob(["*.asc"]),
    deps = [
        "//java/google/registry/util",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@javax_inject",
        "@org_bouncycastle_bcpg_jdk15on",
    ],
)
