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

USAGE="
$(basename "$0") [--help]
or
$(basename "$0") OPTIONS
Rolls back the Registry server on AppEngine to an earlier version

The Gradle task :release:rollback is a convenience wrapper of this script.

Options:
    -h, --help  show this help text
    -d, --dev_project  the GCP project with the deployment infrastructure
    -p, --project  the GCP project to be rolled back
    -e, --env  the name of the deployment environment, e.g., sandbox
    -t, --target  the tag of the target release, e.g., nomulus-20200101-RC00"

function findTargetAppEngineVersion() {
  local dev_project="${1}"
  local deployed_env="${2}"
  local mapping

  mapping=$(gsutil cat \
      "gs://${dev_project}-deployed-tags/nomulus.${deployed_env}.versions" \
      grep "${TARGET}" | head 1)
  if [[ -z "${mapping}" ]]; then
      echo "${TARGET} not found among previous versions in ${PROJECT}."
      exit 1
  fi
  echo "${mapping}" | cut -d' ' -f2
}

SCRIPT_DIR="$(realpath $(dirname $0))"

set -e

eval set -- $(getopt -o p:s:e:h -l project:,sut:,env:,help -- "$@")
while true; do
  case "$1" in
    -p | --project) PROJECT="$2"; shift 2 ;;
    -d | --dev_project) DEV_PROJECT="$2"; shift 2 ;;
    -e | --env) ENV="$2"; shift 2 ;;
    -t | --target) TARGET="$2"; shift 2 ;;
    -h | --help) echo "${USAGE}"; exit 0 ;;
    --) shift; break ;;
    *) echo "${USAGE}"; exit 1 ;;
  esac
done

if [[ -z "${PROJECT}" || -z "${DEV_PROJECT}" || -z "${TARGET}" || -z "${ENV}" ]]
then
   echo "${USAGE}"
   exit 1
fi

appengine_version=$(findTargetAppEngineVersion "${DEV_PROJECT}" "${ENV}")

"${SCRIPT_DIR}/../integration/run_nomulus_rollback_test.sh" -p "${DEV_PROJECT}" \
    -v ${TARGET} -e ${ENV}





