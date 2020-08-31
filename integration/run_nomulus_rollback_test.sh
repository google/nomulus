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
# against the Cloud SQL schema currently deployed in a production environment.
# The target use case is the server rollback process, where this script
# verifies that rollback target is compatible with the current Cloud SQL schema.

USAGE="
$(basename "$0") [--help]
or
$(basename "$0") OPTIONS
Run compatibility test between Nomulus server and Cloud SQL schema.

It is recommended to run this script at HEAD in the master branch. Unlike
./run_compatibility_tests.sh, caller needs not check out any Github tag before
invoking this script.

Options:
    -h, --help  show this help text
    -p, --project
            the GCP project with deployment infrastructure. It should
            take the devProject property defined in the Gradle root
            project.
    -v, --nomulus_version  the release tag of the Nomlus server.
    -e, --env  the environment that should be tested, either sandbox or
               production. If unspecified, both environments will be tested."

SCRIPT_DIR="$(realpath $(dirname $0))"

. "${SCRIPT_DIR}/testutils_bashrc"

set -e

eval set -- $(getopt -o p:v:e:h -l project:,nomulus_version:,env:,help -- "$@")
while true; do
  case "$1" in
    -p | --project) DEV_PROJECT="$2"; shift 2 ;;
    -v | --nomulus_version) NOMULUS_VERSION="$2"; shift 2 ;;
    -e | --env) ENV="$2"; shift 2 ;;
    -h | --help) echo "${USAGE}"; exit 0 ;;
    --) shift; break ;;
    *) echo "${USAGE}"; exit 1 ;;
  esac
done

if [[ -z "${DEV_PROJECT}" || -z "${NOMULUS_VERSION}" || -z "${ENV}" ]]; then
   echo "${USAGE}"
   exit 1
fi

SCHEMA_VERSION=$(fetchVersion sql "${ENV}" "${DEV_PROJECT}")

echo "Running test with -Pnomulus_version=${nomulus_version}" \
    "-Pschema_version=${schema_version} (in ${ENV})"

(cd ${SCRIPT_DIR}/..; \
    ./gradlew :integration:sqlIntegrationTest \
        -PdevProject="${DEV_PROJECT}" \
        -Pnomulus_version="${NOMULUS_VERSION}" \
        -Pschema_version="${SCHEMA_VERSION}" \
        -Ppublish_repo="https://storage.googleapis.com/${DEV_PROJECT}-deployed-tags/maven")
