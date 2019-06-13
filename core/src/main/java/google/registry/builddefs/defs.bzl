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

"""Common routines for Nomulus build rules."""

ZIPPER = "@bazel_tools//tools/zip:zipper"

def long_path(ctx, file_):
    """Constructs canonical runfile path relative to TEST_SRCDIR.

    Args:
      ctx: A Skylark rule context.
      file_: A File object that should appear in the runfiles for the test.

    Returns:
      A string path relative to TEST_SRCDIR suitable for use in tests and
      testing infrastructure.
    """
    if file_.short_path.startswith("../"):
        return file_.short_path[3:]
    if file_.owner and file_.owner.workspace_root:
        return file_.owner.workspace_root + "/" + file_.short_path
    return ctx.workspace_name + "/" + file_.short_path

def collect_runfiles(targets):
    """Aggregates runfiles from targets.

    Args:
      targets: A list of Bazel targets.

    Returns:
      A list of Bazel files.
    """
    data = depset()
    for target in targets:
        if hasattr(target, "runfiles"):
            data += target.runfiles.files
            continue
        if hasattr(target, "data_runfiles"):
            data += target.data_runfiles.files
        if hasattr(target, "default_runfiles"):
            data += target.default_runfiles.files
    return data

def _get_runfiles(target, attribute):
    runfiles = getattr(target, attribute, None)
    if runfiles:
        return runfiles.files
    return []
