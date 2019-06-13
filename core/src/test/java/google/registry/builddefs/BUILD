package(
    default_testonly = 1,
    default_visibility = ["//java/google/registry:registry_project"],
)

licenses(["notice"])  # Apache 2.0

load("//java/google/registry/builddefs:zip_file.bzl", "zip_file")
load("//javatests/google/registry/builddefs:zip_contents_test.bzl", "zip_contents_test")

genrule(
    name = "generated",
    outs = ["generated.txt"],
    cmd = "echo generated >$@",
)

zip_file(
    name = "basic",
    srcs = [
        "generated.txt",
        "hello.txt",
        "world.txt",
    ],
    out = "basic.zip",
    mappings = {"": ""},
)

zip_contents_test(
    name = "zip_emptyMapping_leavesShortPathsInTact",
    src = "basic.zip",
    contents = {
        "domain_registry/javatests/google/registry/builddefs/generated.txt": "generated",
        "domain_registry/javatests/google/registry/builddefs/hello.txt": "hello",
        "domain_registry/javatests/google/registry/builddefs/world.txt": "world",
    },
)

zip_file(
    name = "stripped",
    srcs = ["hello.txt"],
    out = "stripped.zip",
    mappings = {"domain_registry/javatests/google/registry/builddefs": ""},
)

zip_contents_test(
    name = "zip_prefixRemoval_works",
    src = "stripped.zip",
    contents = {"hello.txt": "hello"},
)

zip_file(
    name = "repath",
    srcs = [
        "generated.txt",
        "hello.txt",
        "world.txt",
    ],
    out = "repath.zip",
    mappings = {
        "domain_registry/javatests/google/registry/builddefs": "a/b/c",
        "domain_registry/javatests/google/registry/builddefs/generated.txt": "x/y/z/generated.txt",
    },
)

zip_contents_test(
    name = "zip_pathReplacement_works",
    src = "repath.zip",
    contents = {
        "a/b/c/hello.txt": "hello",
        "a/b/c/world.txt": "world",
        "x/y/z/generated.txt": "generated",
    },
)

zip_file(
    name = "overridden",
    srcs = ["override/hello.txt"],
    out = "overridden.zip",
    mappings = {"domain_registry/javatests/google/registry/builddefs/override": "a/b/c"},
    deps = [":repath"],
)

zip_contents_test(
    name = "zip_fileWithSameMappingAsDependentRule_prefersMyMapping",
    src = "overridden.zip",
    contents = {
        "a/b/c/hello.txt": "OMG IM AN OVERRIDE",
        "a/b/c/world.txt": "world",
        "x/y/z/generated.txt": "generated",
    },
)
