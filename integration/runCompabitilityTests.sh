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
$(basename "$0") --dev_project=... --sut=... --env
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

Arguments:
    --help  show this help text
    --dev_project
            the GCP project with deployment infrastructure. It should
            take the devProject property defined in the Gradle root
            project.
    --sut  the system under test, either sql or nomulus.
    --env  the environment that should be tested, either sandbox or production.
           If unspecified, both environments will be tested."

SCRIPT_DIR="$(realpath $(dirname $0))"

# Fetch the tag of the currently deployed release of Nomulus server
# or SQL schema.
function fetchVersion() {
  local deployed_system=${1}
  local env=${2}
  local dev_project=${3}
  echo $(gsutil cp \
      gs://${dev_project}-deployed-tags/${deployed_system}.${env}.tag -)
}

function getChangeCountSinceVersion() {
  local deployed_system=${1}
  local version=${2}
  local changes

  if [[  ${deployed_system} == "sql " ]]; then
    changes=$(git diff --name-only ${version} \
        db/src/main/resources/sql/flyway | wc -l)
  else
    # TODO(weiminyu): add tests to enforce that jpa managed classes are
    # restricted to the three src folders listed below.
    changes=$(git diff --name-only ${version} \
        core/src/main/resources/META-INF \
        core/src/main/java/google/registry/model \
        core/src/main/java/google/registry/schema \
        core/src/main/java/google/registry/persistence | wc -l)
  fi
  echo ${changes}
}

function runTest() {
  local deployed_system=${1}
  local version=${2}
  local dev_project=${3}

  local changes=$(getChangeCountSinceVersion ${deployed_system} ${version})
  if [[ ${changes} = 0 ]]; then
    echo "No relevant changes in ${deployed_system} since ${version}"
    return 0
  fi

  echo "Found relevant changes in ${deployed_system} since ${version}"

  local nomulus_version
  local schema_version

  if [[ ${deployed_system} = "sql" ]]; then
    schema_version=${version}
    nomulus_version="local"
  else
    nomulus_version=${version}
    schema_version="local"
  fi

  echo "Running test with -Pnomulus_version=${nomulus_version}" \
      "-Pschema_version=${schema_version}"

  (cd ${SCRIPT_DIR}/..; \
      ./gradlew :integration:sqlIntegrationTest \
          -PdevProject=${dev_project} \
          -Pnomulus_version=${nomulus_version} \
          -Pschema_version=${schema_version} \
          -Ppublish_repo=gcs://${dev_project}-deployed-tags/maven)
}

set -e

for i in "$@"; do
  case $i in
    --dev_project=*)
      DEV_PROJECT="${i#*=}"
      ;;
    --sut=*)
      SUT="${i#*=}"
      ;;
    --env=*)
      ENV="${i#*=}"
      ;;
    *)
      ;;
  esac
done

if [[ -z "${DEV_PROJECT}" ]]; then
   echo "${USAGE}"
   exit 1
fi

if [[ "${SUT}" = "nomulus" ]]; then
  DEPLOYED_SYSTEM="sql"
elif [[ "${SUT}" = "sql" ]]; then
  DEPLOYED_SYSTEM="nomulus"
else
  echo "${USAGE}"
  exit 1
fi

if [[ ! -z "${ENV}" ]] && [[ "${ENV}" != "sandbox" ]] \
    && [[ "${ENV}" != "production" ]]; then
  echo "${USAGE}"
  exit 1
fi

if [[ -z "${ENV}" ]]; then
  SANDBOX_VERSION=$(fetchVersion ${DEPLOYED_SYSTEM} sandbox ${DEV_PROJECT})
  PROD_VERSION=$(fetchVersion ${DEPLOYED_SYSTEM} production ${DEV_PROJECT})
  if [[ ${SANDBOX_VERSION} = ${PROD_VERSION} ]]; then
    VERSIONS=(${PROD_VERSION})
  else
    VERSIONS=(${PROD_VERSION} ${SANDBOX_VERSION})
  fi
else
  TARGET_VERSION=$(fetchVersion ${DEPLOYED_SYSTEM} ${ENV} ${DEV_PROJECT})
  VERSIONS=(${TARGET_VERSION})
fi

for v in ${VERSIONS[@]}; do
  runTest ${DEPLOYED_SYSTEM} ${v} ${DEV_PROJECT}
done
