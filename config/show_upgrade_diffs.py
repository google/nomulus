# Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

"""Show the set of dependency diffs introduced by a branch.

Usage:
    show-upgrade-diffs.py <user> <branch> <directory>

Assumes that there is a <user>/nomulus repository on github with the specified
branch name.
"""

import os
import six
import subprocess
import sys
from typing import cast, Dict, Set, Tuple, Union


def run(*args):
    if subprocess.call(args):
        raise Abort(f'"{" ".join(args)}" failed')


PackageName = Tuple[bytes, bytes]
VersionSet = Set[bytes]
PackageMap = Dict[PackageName, VersionSet]


class Abort(Exception):
    """Raised to abort processing and record an error."""


def merge(dest: PackageMap, new: PackageMap) -> None:
    for key, val in new.items():
        dest[key] = dest.setdefault(key, set()) | val


def parse_lockfile(filename: str) -> PackageMap:
    result: PackageMap = {}
    for line in open(filename, 'rb'):
        if line.startswith(b'#'):
            continue
        line = line.rstrip()
        package = cast(Tuple[bytes, bytes, bytes], tuple(line.split(b':')))
        result.setdefault(package[:-1], set()).add(package[-1])
    return result


def get_all_package_versions(dir: str) -> PackageMap:
    """Return list of all package versions in the directory."""
    packages = {}
    for file in os.listdir(dir):
        file = os.path.join(dir, file)
        if file.endswith('.lockfile'):
            merge(packages, parse_lockfile(file))
        elif os.path.isdir(file):
            merge(packages, get_all_package_versions(file))
    return packages


def pr(*args: Union[str, bytes]) -> None:
    """Print replacement that prints bytes without weird conversions."""
    for text in args:
        sys.stdout.buffer.write(six.ensure_binary(text))


def format_versions(a: VersionSet, b: VersionSet, missing_esc: bytes) -> bytes:
    """Returns a formatted string of the elements of "a".

    Returns the elements of "a" as a comma-separated string, colorizes the
    elements of "a" that are not also in "b" with "missing_esc".

    Args:
        a: Elements to print.
        b: Other set, if a printed element is not a member of "b" it is
            colorized.
        missing_esc: ANSI terminal sequence to use to colorize elements that
            are missing from "b".
    """
    elems = []
    for item in a:
        if item in b:
            elems.append(item)
        else:
            elems.append(missing_esc + item + b'\033[0m')
    return b', '.join(elems)


def main():
    # Print usage message on explicity help request or bad arguments.
    want_help = len(sys.argv) > 1 and sys.argv[1] in ('-h', '--help')
    if len(sys.argv) < 4 or want_help:
        print(__doc__)
        sys.exit(0 if want_help else 1)

    user = sys.argv[1]
    branch = sys.argv[2]
    dir = sys.argv[3]

    # Either clone or fetch the master branch if it exists.
    if os.path.exists(dir):
        pr(f'Reusing directory {dir}\n')
        os.chdir(dir)
        run('git', 'fetch', 'git@github.com:google/nomulus', 'master:master')
        run('git', 'checkout', 'master')
    else:
        run('git', 'clone', 'git@github.com:google/nomulus', dir)
        os.chdir(dir)

    old_packages = get_all_package_versions('.')
    run('git', 'fetch', f'https://github.com/{user}/nomulus.git',
        f'{branch}:{branch}')
    run('git', 'checkout', branch)
    new_packages = get_all_package_versions('.')

    if new_packages != old_packages:
        pr('\n\nPackage version change report:\n')
        pr('package-name: {old versions} -> {new versions}\n')
        pr('==============================================\n\n')
        for package, new_versions in new_packages.items():
            old_versions = old_packages.get(package)
            if new_versions != old_versions:

                # Print out "package-name: {old versions} -> {new versions} with
                # pretty colors.
                formatted_old_versions = (
                    format_versions(old_versions, new_versions,
                                    b'\033[40;31;1m'))
                formatted_new_versions = (
                    format_versions(new_versions,
                                    old_versions, b'\033[40;32;1m'))
                pr(b':'.join(package), ': {', formatted_old_versions, '} -> {',
                formatted_new_versions, '}\n')

        # Print the list of packages that were removed.
        for package in old_packages:
            if package not in new_packages:
                pr(b':'.join(package), ' removed\n')
    else:
        pr('Package versions not updated!\n')

    pr(f'\nRetaining git directory {dir}, to delete: rm -rf {dir}\n')


if __name__ == '__main__':
    main()
