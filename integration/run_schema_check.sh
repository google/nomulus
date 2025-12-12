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
edited, renamed, or deleted. This script checks for changes to scripts that have
already been deployed to Sandbox. The assumption is that the schema in Sandbox
is always at least as recent as that in production. Please refer to Gradle task
:db:schemaIncrementalDeployTest for more information.

Note that this test MAY fail to catch forbidden changes during the period when
a new schema release is created but not yet deployed to Sandbox.

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

if [[ -n "${SCHEMA_TEST_ARTIFACTS_DIR}" ]]; then
  echo "Using schema test jars downloaded to ${SCHEMA_TEST_ARTIFACTS_DIR}"
else
  SCHEMA_TEST_ARTIFACTS_DIR=$(mktemp -d)
  echo "Created working dir ${SCHEMA_TEST_ARTIFACTS_DIR} for downloaded test jars."
  trap 'rm -rf ${SCHEMA_TEST_ARTIFACTS_DIR}' EXIT
  gcloud storage cp --verbosity=none \
      "gs://${DEV_PROJECT}-deployed-tags/schema-test-artifacts/*.jar" \
      "${SCHEMA_TEST_ARTIFACTS_DIR}"
fi

(cd ${SCRIPT_DIR}/..; \
    ./gradlew :db:schemaIncrementalDeployTest \
        -PschemaTestArtifactsDir="${SCHEMA_TEST_ARTIFACTS_DIR}")
