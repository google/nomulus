workspace(name = "domain_registry")

load("//java/google/registry:repositories.bzl", "domain_registry_repositories")

domain_registry_repositories()

http_archive(
    name = "io_bazel_rules_closure",
    sha256 = "c26721a7d47c90cb7d28b704718b27f7dff6ee654f795c5224d3c4e412a4c631",
    strip_prefix = "rules_closure-a0ec40ee36d824da50d39ff5bd054d395e814649",
    url = "http://bazel-mirror.storage.googleapis.com/github.com/bazelbuild/rules_closure/archive/a0ec40ee36d824da50d39ff5bd054d395e814649.tar.gz",  # 2016-12-06
)

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_repositories")

closure_repositories(
    omit_gson = True,
    omit_guava = True,
    omit_icu4j = True,
    omit_jetty = True,
    omit_jetty_util = True,
    omit_json = True,
    omit_jsr305 = True,
    omit_jsr330_inject = True,
    omit_servlet_api = True,
)
