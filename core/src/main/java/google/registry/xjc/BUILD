package(
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/google/registry/xjc:generated_files.bzl", "pkginfo_generated_files", "xjc_generated_files")

java_library(
    name = "xjc",
    srcs = glob(["*.java"]) + xjc_generated_files + pkginfo_generated_files,
    visibility = ["//visibility:public"],
    deps = [
        "//java/google/registry/xml",
        "//third_party/jaxb",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava",
        "@joda_time",
    ],
)

sh_binary(
    name = "list_generated_files",
    srcs = ["list_generated_files.sh"],
    data = [
        "bindings.xjb",
        "//java/google/registry/xml:xml_schema_files",
        "//third_party/jaxb:jaxb-xjc",
    ],
)

genrule(
    name = "jaxb_srcs",
    srcs = [
        "bindings.xjb",
        "//java/google/registry/xml:xml_schema_files",
    ],
    outs = xjc_generated_files,
    cmd = " && ".join([
        "REP=$$(pwd)",
        "XJC=$$REP/$(location //third_party/jaxb:jaxb-xjc)",
        "OUT=$$REP/$(GENDIR)/java",
        "TMP=$$(mktemp -d $${TMPDIR:-/tmp}/jaxb.XXXXXXXXXX)",
        "mkdir -p $$OUT",
        "cp $(SRCS) $$TMP",
        "cd $$TMP",
        "$$XJC -npa -quiet -extension -d $$OUT -b *.xjb *.xsd",
        ("find $$OUT -name '*.java' | xargs sed -i.bak 's/" +
         "public class/" +
         "@SuppressWarnings({\"unchecked\",\"serial\",\"cast\"})\\\n" +
         "public class/'"),
        "cd $$REP",
        "rm -rf $$TMP",
    ]),
    tools = ["//third_party/jaxb:jaxb-xjc"],
)

sh_binary(
    name = "make_pkginfo",
    srcs = ["make_pkginfo.sh"],
)

genrule(
    name = "pkginfo_srcs",
    srcs = ["package-info.java.in"],
    outs = pkginfo_generated_files,
    cmd = "$(location :make_pkginfo) $(SRCS) $(@D)",
    tools = [
        ":jaxb_srcs",
        ":make_pkginfo",
    ],
)
