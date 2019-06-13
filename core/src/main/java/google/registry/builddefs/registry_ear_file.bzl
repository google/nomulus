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

"""Build macro for creating App Engine EAR archives for Nomulus."""

load("//java/google/registry/builddefs:defs.bzl", "ZIPPER")

def registry_ear_file(name, out, configs, wars, **kwargs):
    """Creates an EAR archive by combining WAR archives."""
    cmd = [
        "set -e",
        "repo=$$(pwd)",
        "zipper=$$repo/$(location %s)" % ZIPPER,
        "tmp=$$(mktemp -d $${TMPDIR:-/tmp}/registry_ear_file.XXXXXXXXXX)",
        "cd $${tmp}",
    ]
    for target, dest in configs.items():
        cmd += [
            "mkdir -p $${tmp}/$$(dirname %s)" % dest,
            "ln -s $${repo}/$(location %s) $${tmp}/%s" % (target, dest),
        ]
    for target, dest in wars.items():
        cmd += [
            "mkdir " + dest,
            "cd " + dest,
            "$${zipper} x $${repo}/$(location %s)" % target,
            "cd ..",
        ]
    cmd += [
        "$${zipper} cC $${repo}/$@ $$(find . | sed 1d | cut -c 3- | LC_ALL=C sort)",
        "cd $${repo}",
        "rm -rf $${tmp}",
    ]
    native.genrule(
        name = name,
        srcs = configs.keys() + wars.keys(),
        outs = [out],
        cmd = "\n".join(cmd),
        tools = [ZIPPER],
        message = "Generating EAR archive",
        **kwargs
    )
