#!/usr/bin/env python3
# Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import sys
import re
import os

def usage():
    print("Usage: python safe_rename.py <filepath> <old_name> <new_name>")
    print("Safely renames an identifier in a Java file, ignoring strings and comments.")
    sys.exit(1)

def main():
    if len(sys.argv) < 4:
        usage()

    filepath = sys.argv[1]
    old_name = sys.argv[2]
    new_name = sys.argv[3]

    if not os.path.exists(filepath):
        print(f"Error: File {filepath} not found.")
        sys.exit(1)

    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Regex to tokenize Java source safely.
    token_pattern = re.compile(
        r'(?P<string>"(?:\\.|[^"\\])*")|'
        r'(?P<char>\'(?:\\.|[^\'\\])*\')|'
        r'(?P<line_comment>//.*)|'
        r'(?P<block_comment>/\*[\s\S]*?\*/)|'
        r'(?P<ident>[a-zA-Z_$][a-zA-Z0-9_$]*)'
    )

    def replacer(match):
        if match.group('ident') == old_name:
            return new_name
        return match.group(0)

    new_content = token_pattern.sub(replacer, content)

    if content == new_content:
        print(f"No occurrences of '{old_name}' found to rename in {filepath}.")
    else:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Successfully renamed '{old_name}' to '{new_name}' in {filepath}.")

if __name__ == '__main__':
    main()
