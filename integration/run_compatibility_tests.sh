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
Run compatibility test between Nomulus server and Cloud SQL schema.

The --sut option specifies a system under test, which may be either nomulus
or sql. If sut is nomulus, the Nomulus server code in the local Git branch
must be able to work with deployed SQL schemas. This is verified by running
the sqlIntegrationTestSuite in the local branch with schemas already
deployed to sandbox and/or production.

If sut is sql, the schema in local branch must not break the currently
deployed servers. This is verified by running the sqlIntegrationTestSuite
in appropriate releases against the SQL schema in the local branch.

This script needs to fetch Github tags of deployed systems. On platforms
that performs shallow clone, such as Travis-ci, caller may need to invoke
'git fetch --tags'.

Options:
    -h, --help  show this help text
    -p, --project
            the GCP project with deployment infrastructure. It should
            take the devProject property defined in the Gradle root
            project.
    -s, --sut  the system under test, either sql or nomulus.
    -e, --env  the environment that should be tested, either sandbox or
               production. If unspecified, both environments will be tested."

SCRIPT_DIR="$(realpath $(dirname $0))"

. "${SCRIPT_DIR}/testutils_bashrc"

function runTest() {
  local deployed_system=${1}
  local version=${2}
  local dev_project=${3}
  local env=${4}

#  local changes=$(getChangeCountSinceVersion ${deployed_system} ${version})
#  if [[ ${changes} = 0 ]]; then
#    echo "No relevant changes in ${deployed_system} since ${version}"
#    return 0
#  fi
#
#  echo "Found relevant changes in ${deployed_system} since ${version}"

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

  local nomulus_env
  local schema_env

  if [[ ${deployed_system} = "sql" ]]; then
    schema_env=${env}
    nomulus_env="local"
  else
    nomulus_env=${env}
    schema_env="local"
  fi

  echo "Running test with -Pnomulus_env=${nomulus_env}" \
      "-Pschema_env=${schema_env}" \
      "-PschemaTestArtifactsDir=${SCHEMA_TEST_ARTIFACTS_DIR}" \

  # The https scheme in the Maven repo URL below is required for Kokoro. See
  # ./run_schema_check.sh for more information.
  (cd ${SCRIPT_DIR}/..; \
      ./gradlew :integration:sqlIntegrationTest \
          -PdevProject=${dev_project} \
          -Pnomulus_env=${nomulus_env} \
          -Pschema_env=${schema_env} \
          -PschemaTestArtifactsDir=${SCHEMA_TEST_ARTIFACTS_DIR})
}

set -e

eval set -- $(getopt -o p:s:e:h -l project:,sut:,env:,help -- "$@")
while true; do
  case "$1" in
    -p | --project) DEV_PROJECT="$2"; shift 2 ;;
    -s | --sut) SUT="$2"; shift 2 ;;
    -e | --env) ENV="$2"; shift 2 ;;
    -h | --help) echo "${USAGE}"; exit 0 ;;
    --) shift; break ;;
    *) echo "${USAGE}"; exit 1 ;;
  esac
done

if [[ -z "${DEV_PROJECT}" ]]; then
   echo "${USAGE}"
   exit 1
fi

if [[ "${SUT}" = "nomulus" ]]; then
  DEPLOYED_SYSTEM="sql"
elif [[ "${SUT}" = "sql" ]]; then
  DEPLOYED_SYSTEM="nomulus-gke"
else
  echo "${USAGE}"
  exit 1
fi

if [[ ! -z "${ENV}" ]] && [[ "${ENV}" != "sandbox" ]] \
    && [[ "${ENV}" != "production" ]]; then
  echo "${USAGE}"
  exit 1
fi

echo "Testing ${SUT} against deployed ${DEPLOYED_SYSTEM} versions:"
if [[ -z "${ENV}" ]]; then
  SANDBOX_VERSION=$(fetchVersion ${DEPLOYED_SYSTEM} sandbox ${DEV_PROJECT})
  PROD_VERSION=$(fetchVersion ${DEPLOYED_SYSTEM} production ${DEV_PROJECT})
  if [[ ${SANDBOX_VERSION} = ${PROD_VERSION} ]]; then
    echo "- sandbox and production at ${PROD_VERSION}"
    runTest ${DEPLOYED_SYSTEM} ${SANDBOX_VERSION} ${DEV_PROJECT} sandbox
  else
    echo "- sandbox at ${SANDBOX_VERSION}"
    runTest ${DEPLOYED_SYSTEM} ${SANDBOX_VERSION} ${DEV_PROJECT} sandbox
    echo "- production at ${PROD_VERSION}"
    runTest ${DEPLOYED_SYSTEM} ${PROD_VERSION} ${DEV_PROJECT} production
  fi
else
  TARGET_VERSION=$(fetchVersion ${DEPLOYED_SYSTEM} ${ENV} ${DEV_PROJECT})
  echo "- ${ENV} at ${TARGET_VERSION}"
  runTest ${DEPLOYED_SYSTEM} ${TARGET_VERSION} ${DEV_PROJECT} ${ENV}
fi
