# Top-level directory for python code in the domain registry project.
#
# Code within this directory should live under python/google/registry.
#
# For an example of another Google open-source Bazel-based project using a
# top-level python directory in a similar way, see
#   https://github.com/google/protobuf/tree/master/python

package(default_visibility = ["//java/google/registry:registry_project"])

licenses(["notice"])  # Apache 2.0

# NOTE: All py_library() rules under this directory should depend on this rule.
#
# This rule serves to add this directory (/python) to the PYTHONPATH of any
# python executable rules that transitively depend on this rule.  Adding this
# directory ensures that imports for "google.registry.*" can be correctly
# resolved to files that are under //python/google/registry.
#
# See also:
#   - http://www.bazel.io/docs/be/python.html#py_library.imports
#   - https://github.com/google/protobuf/blob/v3.0.0-beta-3/BUILD#L568
py_library(
    name = "python_directory_import",
    imports = ["."],
)
