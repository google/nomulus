#!/bin/bash
# Copyright 2020 The Nomulus Authors. All Rights Reserved.
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
#
# This script runs the sqlIntegrationTestSuite in a given server release
# against a specific Cloud SQL schema release. When invoked during presubmit
# tests, it detects code or schema changes that are incompatible with current
# deployments in production.

USAGE="
$(basename "$0") [--help]
or
$(basename "$0") OPTIONS
Checks for post-deployment change to Flyway scripts.

With Flyway, once an incremental change script is deployed, it must not be
changed. Even changes to comments or whitespaces would cause validation
failures during future deployment. This script checks for changes (including
removal and renaming which may happen due to incorrect merge conflict
resolution) to scripts that have already been deployed to Sandbox. The
assumption is that the schema in Sandbox is always newer than that in
production.

A side-effect of this check is that old branches missing recently deployed
scripts must update first.

Options:
    -h, --help  show this help text
    -p, --project
            the GCP project with deployment infrastructure. It should
            take the devProject property defined in the Gradle root
            project."

SCRIPT_DIR="$(realpath $(dirname $0))"

. "${SCRIPT_DIR}/testutils_bashrc"

set -e

eval set -- $(getopt -o p:s:e:h -l project:,sut:,env:,help -- "$@")
while true; do
  case "$1" in
    -p | --project) DEV_PROJECT="$2"; shift 2 ;;
    -h | --help) echo "${USAGE}"; exit 0 ;;
    --) shift; break ;;
    *) echo "${USAGE}"; exit 1 ;;
  esac
done

if [[ -z "${DEV_PROJECT}" ]]; then
   echo "${USAGE}"
   exit 1
fi

sandbox_tag=$(fetchVersion sql sandbox ${DEV_PROJECT})
echo "Checking Flyway scripts against schema in Sandbox (${sandbox_tag})."

if git diff ${sandbox_tag} db/src/main/resources/sql/flyway.dat | \
   awk -e '
       # Script to verify that the only changes since the last version are
       # file additions.
       BEGIN { got_addition = 0 }

       # If the line starts with a hypen, something has been removed.  That
       # should never happen.
       /^-/ { exit 1 }

       # If the line starts with a plus, something has been added.  That is ok
       # as long as the first thing added is a #file line and it was added to
       # the end of the file, so we check for the former and record the fact
       # that we got an addition.
       /^+/ {
          # To be added back after initial commit gets tagged for
          # sandbox (verifies that the first addition is a #file line)
          #if (!got_addition && substr($0, 1, 6) != "#file ") {
          #  exit 1
          #}
          got_addition = 1;
       }

       # If the line starts with a space, it is a context line (i.e. not a
       # changed line).  We should only see context lines before an addition.
       # If we ever see it after an addition, that means that lines have been
       # inserted into the existing files rather than appended.
       /^ / { if (got_addition) exit 1 }

       END { exit 0 }'
then
  echo "No illegal change to deployed schema scripts."
else
  echo "This change is not allows:"
  git diff ${sandbox_tag} db/src/main/resources/sql/flyway.dat
  echo "Make sure your branch is up to date with HEAD of master."
  exit 1
fi

echo "Checking that no flyway scripts have been added to the original source "
echo "directory."
if git diff --name-status ${sandbox_tag} \
       db/src/main/resources/sql/flyway/* | \
   grep '^[AM]'; then
  echo 'Adding files directly to the flyway directory is not allowed.  ' \
       'Please add your file contents to the end of ' \
       'db/src/main/resources/sql/flyway.dat instead.'
  exit 1
else
  echo 'No scripts added to original source dir.'
  exit 0
fi
