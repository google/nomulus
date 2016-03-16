package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # BSD

config_setting(
    name = "darwin",
    values = {"cpu": "darwin"},
    visibility = ["//visibility:public"],
)

config_setting(
    name = "darwin_x86_64",
    values = {"cpu": "darwin_x86_64"},
    visibility = ["//visibility:public"],
)

genrule(
    name = "phantomjs_bin",
    srcs = select({
        ":darwin": ["@phantomjs_mac//file"],
        ":darwin_x86_64": ["@phantomjs_mac//file"],
        "//conditions:default": ["@phantomjs_linux//file"]
    }),
    outs = ["phantomjs"],
    cmd = " && ".join([
            "IN=$$(pwd)/$(SRCS)",
            "OUT=$$(pwd)/$@",
            "TMP=$$(mktemp -d $${TMPDIR:-/tmp}/phantomjs.XXXXXXXX)",
            "cd $$TMP"
        ]) + select({
            ":darwin": " && unzip $$IN && ",
            ":darwin_x86_64": " && unzip $$IN && ",
            "//conditions:default": " && tar -xjf $$IN && "
        }) + " && ".join([
            "cd phantomjs-*",
            "mv bin/phantomjs $$OUT",
            "rm -rf $$TMP",
    ]),
)
