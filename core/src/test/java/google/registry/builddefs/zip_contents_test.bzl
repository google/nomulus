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

"""Build rule for unit testing the zip_file() rule."""

load("//java/google/registry/builddefs:defs.bzl", "ZIPPER")

def _impl(ctx):
    """Implementation of zip_contents_test() rule."""
    cmd = [
        "set -e",
        'repo="$(pwd)"',
        'zipper="${repo}/%s"' % ctx.file._zipper.short_path,
        'archive="${repo}/%s"' % ctx.file.src.short_path,
        ('listing="$("${zipper}" v "${archive}"' +
         ' | grep -v ^d | awk \'{print $3}\' | LC_ALL=C sort)"'),
        'if [[ "${listing}" != "%s" ]]; then' % (
            "\n".join(ctx.attr.contents.keys())
        ),
        '  echo "archive had different file listing:"',
        '  "${zipper}" v "${archive}" | grep -v ^d',
        "  exit 1",
        "fi",
        'tmp="$(mktemp -d "${TMPDIR:-/tmp}/zip_contents_test.XXXXXXXXXX")"',
        'cd "${tmp}"',
        '"${zipper}" x "${archive}"',
    ]
    for path, data in ctx.attr.contents.items():
        cmd += [
            'if [[ "$(cat "%s")" != "%s" ]]; then' % (path, data),
            '  echo "%s had different contents:"' % path,
            '  cat "%s"' % path,
            "  exit 1",
            "fi",
        ]
    cmd += [
        'cd "${repo}"',
        'rm -rf "${tmp}"',
    ]
    ctx.actions.write(
        output = ctx.outputs.executable,
        content = "\n".join(cmd),
        is_executable = True,
    )
    return struct(runfiles = ctx.runfiles([ctx.file.src, ctx.file._zipper]))

zip_contents_test = rule(
    implementation = _impl,
    test = True,
    attrs = {
        "src": attr.label(allow_single_file = True),
        "contents": attr.string_dict(),
        "_zipper": attr.label(default = Label(ZIPPER), allow_single_file = True),
    },
)
