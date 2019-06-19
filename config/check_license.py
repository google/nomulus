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

license = r".*Copyright \d{4} The Nomulus Authors\. All Rights Reserved\."
extensions = ("java", "js", "soy", "sql", "py", "sh")
non_included_patterns = {".git", "/build/", "/generated/", "node_modules/", "JUnitBackports.java", }

def should_include_path(path):
    for pattern in non_included_patterns:
        if pattern in path: return False
    return path.endswith(extensions)

def get_files():
    result = []
    for root, dirnames, filenames in os.walk("."):
        paths = [os.path.join(root, filename) for filename in filenames]
        result.extend(filter(should_include_path, paths))
    return result

if __name__ == "__main__":
    all_files = get_files()
    failed_files = []

    for file in all_files:
        with open(file, 'r') as f:
            file_content = f.read()
            if not re.match(license, file_content, re.DOTALL):
                failed_files.append(file)

    if failed_files:
        print("The following files did not match the license header: " + str(failed_files))
        sys.exit(1)
