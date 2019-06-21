# Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import os
import sys
import re

UNIVERSALLY_SKIPPED_PATTERNS = {"/build/", "/out/"}
# We can't rely on CI to have the Enum package installed so we do this instead.
__FORBIDDEN__ = 1
__REQUIRED__ = 2

class PresubmitCheck:
    def __init__(self, regex, included_extensions, skipped_patterns, regex_type = __FORBIDDEN__):
        self.regex = regex
        self.included_extensions = included_extensions
        self.skipped_patterns = skipped_patterns
        self.regex_type = regex_type

    def get_failed_files(self, files):
        return [file for file in files if self._fails(file)]

    def _fails(self, file):
        if not file.endswith(self.included_extensions): return False
        for pattern in UNIVERSALLY_SKIPPED_PATTERNS:
            if pattern in file: return False
        for pattern in self.skipped_patterns:
            if pattern in file: return False
        with open(file, 'r') as f:
            file_content = f.read()
            matches = re.match(self.regex, file_content, re.DOTALL)
            if self.regex_type == __FORBIDDEN__: return matches
            return not matches

PRESUBMITS = {
    # License check
    PresubmitCheck(
        r".*Copyright 20\d{2} The Nomulus Authors\. All Rights Reserved\.",
        ("java", "js", "soy", "sql", "py", "sh"),
        {".git", "/build/", "/generated/", "node_modules/", "JUnitBackports.java"},
        __REQUIRED__
    ): "The following files did not match the license header",

    # System.(out|err).println should only appear in tools/
    PresubmitCheck(
        r".*\bSystem\.(out|err)\.print",
        "java",
        {"StackdriverDashboardBuilder.java", "/tools/", "/example/", "RegistryTestServerMain.java", "TestServerRule.java", "FlowDocumentationTool.java"}
    ): "System.(out|err).println is only allowed in tools/ packages. Please use a logger instead.",

    # Various Soy linting checks
    PresubmitCheck(
        ".* *(/\*)?\* {?@param ",
        "soy",
        {},
    ): "In SOY please use the ({@param name: string} /** User name. */) style parameter passing instead of the ( * @param name User name.) style parameter pasing.",
    PresubmitCheck(
        '.*[{][^}]+\w+:\s+"',
        "soy",
        {},
    ): "Please don't use double-quoted string literals in Soy parameters",
    PresubmitCheck(
        '.*autoescape\s*=\s*"[^s]',
        "soy",
        {},
    ): "All soy templates must use strict autoescaping",
    PresubmitCheck(
        ".*noAutoescape",
        "soy",
        {},
    ): "All soy templates must use strict autoescaping",

    # various JS linting checks
    PresubmitCheck(
        ".*goog\.base\(",
        "js",
        {"/node_modules/"},
    ): "Use of goog.base is not allowed.",
    PresubmitCheck(
        ".*goog\.dom\.classes",
        "js",
        {"/node_modules/"},
    ): "Instead of goog.dom.classes, use goog.dom.classlist which is smaller and faster.",
    PresubmitCheck(
        ".*goog\.getMsg",
        "js",
        {"/node_modules/"},
    ): "Put messages in Soy, instead of using goog.getMsg().",
    PresubmitCheck(
        ".*(innerHTML|outerHTML)\s*(=|[+]=)([^=]|$)",
        "js",
        {"/node_modules/"},
    ): "Do not assign directly to the dom. Use goog.dom.setTextContent to set to plain text, goog.dom.removeChildren to clear, or soy.renderElement to render anything else",
    PresubmitCheck(
        ".*console\.(log|info|warn|error)",
        "js",
        {"/node_modules/", "google/registry/ui/js/util.js"},
    ): "JavaScript files should not include console logging."
}

def get_files():
    result = []
    for root, dirnames, filenames in os.walk("."):
        paths = [os.path.join(root, filename) for filename in filenames]
        result.extend(paths)
    return result

if __name__ == "__main__":
    all_files = get_files()
    failed = False
    for presubmit, error_message in PRESUBMITS.items():
        failed_files = presubmit.get_failed_files(all_files)
        if failed_files:
            failed = True
            print(error_message)
            print(failed_files)

    if failed:
        sys.exit(1)


