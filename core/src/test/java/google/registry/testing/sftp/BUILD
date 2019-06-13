package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "sftp",
    srcs = glob(["*.java"]),
    deps = [
        "//java/google/registry/util",
        "@com_google_code_findbugs_jsr305",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@junit",
        "@org_apache_ftpserver_core",
        "@org_apache_sshd_core",
        "@org_apache_sshd_scp",
        "@org_apache_sshd_sftp",
        "@org_bouncycastle_bcpkix_jdk15on",
    ],
)
