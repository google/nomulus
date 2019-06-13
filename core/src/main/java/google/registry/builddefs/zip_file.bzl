# Copyright 2017 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Zip file creator that allows arbitrary path renaming.

This rule takes two main inputs: a bunch of filesets and a dictionary of
hard-coded source to dest mappings. It then applies those mappings to the input
file paths, to create a zip file with the same name as the rule.

The following preconditions must be met:

- Sources and destinations can't begin or end with slash.
- Every file must be matched by a mapping.
- Every mapping must match something.

The source can either be an exact match or a prefix.

- If a match is exact, the destination replaces the entire path. If the
  destination path is empty, then the path remains the same.

- If the match is a prefix, then the destination replaces the source prefix in
  the path. If the destination is empty, then the source prefix is removed.

- If source is an empty string, it matches everything. In this case,
  destination becomes the path prefix.

Prefixes are matched with component granularity, not characters. Mappings with
more components take precedence. Mappings with equal components are sorted
asciibetically.

Mappings apply to the "long path" of a file, i.e. relative to TEST_SRCDIR,
e.g. workspace_name/pkg/file. Long paths do not take into consideration
bazel-foo/ output directories.

The deps attribute allows zip_file() rules to depend on other zip_file() rules.
In such cases, the contents of directly dependent zip files are unzipped and
then re-zipped. Mappings specified by the current rule do not apply to the
files extracted from dependent zips. However those files can be overridden.

The simplest example of this rule, which simply zips up short paths, is as
follows:

  # //my/package/BUILD
  zip_file(
      name = "doodle",
      srcs = ["hello.txt"],
      mappings = {"": ""},
  )

The rule above would create a zip file name //my/package/doodle.zip which would
contain a single file named "my/package/hello.txt".

If we wanted to strip the package path, we could do the following:

  # //my/package/BUILD
  zip_file(
      name = "doodle",
      srcs = ["hello.txt"],
      mappings = {"my/package": ""},
  )

In this case, doodle.zip would contain a single file: "hello.txt".

If we wanted to rename hello.txt, we could do the following:

  # //my/package/BUILD
  zip_file(
      name = "doodle",
      srcs = ["hello.txt"],
      mappings = {"my/package/hello.txt": "my/package/world.txt"},
  )

A zip file can be assembled across many rules. For example:

  # //webapp/html/BUILD
  zip_file(
      name = "assets",
      srcs = glob(["*.html"]),
      mappings = {"webapp/html": ""},
  )

  # //webapp/js/BUILD
  zip_file(
      name = "assets",
      srcs = glob(["*.js"]),
      mappings = {"webapp/js": "assets/js"},
  )

  # //webapp/BUILD
  zip_file(
      name = "war",
      deps = [
          "//webapp/html:assets",
          "//webapp/js:assets",
      ],
      mappings = {"webapp/html": ""},
  )

You can exclude files with the "exclude" attribute:

  # //webapp/BUILD
  zip_file(
      name = "war_without_tears",
      deps = ["war"],
      exclude = ["assets/js/tears.js"],
  )

Note that "exclude" excludes based on the mapped path relative to the root of
the zipfile. If the file doesn't exist, you'll get an error.

"""

load(
    "//java/google/registry/builddefs:defs.bzl",
    "ZIPPER",
    "collect_runfiles",
    "long_path",
)

def _zip_file(ctx):
    """Implementation of zip_file() rule."""
    for s, d in ctx.attr.mappings.items():
        if (s.startswith("/") or s.endswith("/") or
            d.startswith("/") or d.endswith("/")):
            fail("mappings should not begin or end with slash")
    srcs = depset()
    srcs += ctx.files.srcs
    srcs += ctx.files.data
    srcs += collect_runfiles(ctx.attr.data)
    mapped = _map_sources(ctx, srcs, ctx.attr.mappings)
    cmd = [
        "#!/bin/sh",
        "set -e",
        'repo="$(pwd)"',
        'zipper="${repo}/%s"' % ctx.file._zipper.path,
        'archive="${repo}/%s"' % ctx.outputs.out.path,
        'tmp="$(mktemp -d "${TMPDIR:-/tmp}/zip_file.XXXXXXXXXX")"',
        'cd "${tmp}"',
    ]
    cmd += [
        '"${zipper}" x "${repo}/%s"' % dep.zip_file.path
        for dep in ctx.attr.deps
    ]
    cmd += ["rm %s" % filename for filename in ctx.attr.exclude]
    cmd += [
        'mkdir -p "${tmp}/%s"' % zip_path
        for zip_path in depset(
            [
                zip_path[:zip_path.rindex("/")]
                for _, zip_path in mapped
                if "/" in zip_path
            ],
        ).to_list()
    ]
    cmd += [
        'ln -sf "${repo}/%s" "${tmp}/%s"' % (path, zip_path)
        for path, zip_path in mapped
    ]
    cmd += [
        ("find . | sed 1d | cut -c 3- | LC_ALL=C sort" +
         ' | xargs "${zipper}" cC "${archive}"'),
        'cd "${repo}"',
        'rm -rf "${tmp}"',
    ]
    if hasattr(ctx, "bin_dir"):
        script = ctx.new_file(ctx.bin_dir, "%s.sh" % ctx.label.name)
    else:
        # TODO(kchodorow): remove this once Bazel 4.0+ is required.
        script = ctx.new_file(ctx.configuration.bin_dir, "%s.sh" % ctx.label.name)
    ctx.actions.write(output = script, content = "\n".join(cmd), is_executable = True)
    inputs = [ctx.file._zipper]
    inputs += [dep.zip_file for dep in ctx.attr.deps]
    inputs += srcs.to_list()
    ctx.actions.run(
        inputs = inputs,
        outputs = [ctx.outputs.out],
        executable = script,
        mnemonic = "zip",
        progress_message = "Creating zip with %d inputs %s" % (
            len(inputs),
            ctx.label,
        ),
    )
    return struct(files = depset([ctx.outputs.out]), zip_file = ctx.outputs.out)

def _map_sources(ctx, srcs, mappings):
    """Calculates paths in zip file for srcs."""

    # order mappings with more path components first
    mappings = sorted([
        (-len(source.split("/")), source, dest)
        for source, dest in mappings.items()
    ])

    # get rid of the integer part of tuple used for sorting
    mappings = [(source, dest) for _, source, dest in mappings]
    mappings_indexes = range(len(mappings))
    used = {i: False for i in mappings_indexes}
    mapped = []
    for file_ in srcs:
        run_path = long_path(ctx, file_)
        zip_path = None
        for i in mappings_indexes:
            source = mappings[i][0]
            dest = mappings[i][1]
            if not source:
                if dest:
                    zip_path = dest + "/" + run_path
                else:
                    zip_path = run_path
            elif source == run_path:
                if dest:
                    zip_path = dest
                else:
                    zip_path = run_path
            elif run_path.startswith(source + "/"):
                if dest:
                    zip_path = dest + run_path[len(source):]
                else:
                    zip_path = run_path[len(source) + 1:]
            else:
                continue
            used[i] = True
            break
        if not zip_path:
            fail("no mapping matched: " + run_path)
        mapped += [(file_.path, zip_path)]
    for i in mappings_indexes:
        if not used[i]:
            fail('superfluous mapping: "%s" -> "%s"' % mappings[i])
    return mapped

zip_file = rule(
    implementation = _zip_file,
    output_to_genfiles = True,
    attrs = {
        "out": attr.output(mandatory = True),
        "srcs": attr.label_list(allow_files = True),
        "data": attr.label_list(allow_files = True),
        "deps": attr.label_list(providers = ["zip_file"]),
        "exclude": attr.string_list(),
        "mappings": attr.string_dict(),
        "_zipper": attr.label(default = Label(ZIPPER), allow_single_file = True),
    },
)
