#!/bin/bash
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
#
# This script runs a build through a manual Cloud Build trigger of this
# repository, and waits for it to finish. Unlike a build of an uploaded tree, a
# trigger build records the source repository, the commit and the config file
# in the build provenance of its images.
#
# release/cloudbuild-release.yaml uses it to build the images of a release
# through the nomulus-images or proxy-images trigger at the release commit, and
# waits for that build before it promotes the images.
#
# Usage:
#   run_trigger_build.sh start <trigger> <commit_sha> <tag_name>
#     Runs <trigger> at <commit_sha>, a full commit SHA of 40 lowercase hex
#     digits, with TAG_NAME=<tag_name>. Prints only the build ID on stdout, so
#     that callers can capture it, and logs the build's log URL on stderr.
#   run_trigger_build.sh wait <build_id>
#     Checks the status of the build every 30 seconds until the build ends.
#     Succeeds if the build succeeds. Fails if the build fails, or if its status
#     can't be read 10 times in a row. Logs on stderr.

set -e

# The region of the triggers, and so of their builds.
readonly REGION=global
# The image builds take several minutes, so a check every 30 seconds is enough.
readonly POLL_SECONDS=30
# Ten failed checks in a row, about five minutes' worth, mean that the build's
# status can't be read, rather than that a check failed transiently.
readonly MAX_FAILED_CHECKS=10

usage() {
  cat >&2 <<EOF
Usage:
  $0 start <trigger> <commit_sha> <tag_name>
  $0 wait <build_id>
EOF
  exit 1
}

# Prints a field of a build, e.g. status or logUrl.
describe_build() {
  gcloud builds describe "$1" --region="${REGION}" --format="value($2)"
}

# Prints the URL of a build's log, for the caller's log.
log_url() {
  local url
  if ! url="$(describe_build "$1" logUrl)"; then
    url=''
  fi
  echo "${url:-(log URL unavailable)}"
}

start_build() {
  (( $# == 3 )) || usage
  local trigger="$1" commit_sha="$2" tag_name="$3" build_id
  if [[ ! "${commit_sha}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "<commit_sha> must be 40 lowercase hex digits, not '${commit_sha}'." \
      "Manual runs of cloudbuild-release.yaml must set COMMIT_SHA." >&2
    exit 1
  fi
  if [[ -z "${tag_name}" ]]; then
    echo "<tag_name> must not be empty." >&2
    exit 1
  fi
  build_id="$(gcloud builds triggers run "${trigger}" --region="${REGION}" \
    --sha="${commit_sha}" --substitutions="TAG_NAME=${tag_name}" \
    --format='value(metadata.build.id)')"
  if [[ -z "${build_id}" ]]; then
    echo "Trigger ${trigger} returned no build ID." >&2
    exit 1
  fi
  echo "Started build ${build_id} of trigger ${trigger} at ${commit_sha}:" \
    "$(log_url "${build_id}")" >&2
  echo "${build_id}"
}

wait_for_build() {
  (( $# == 1 )) || usage
  local build_id="$1" status failed_checks=0
  if [[ -z "${build_id}" ]]; then
    echo "<build_id> must not be empty." >&2
    exit 1
  fi
  while true; do
    if ! status="$(describe_build "${build_id}" status)"; then
      status='(describe failed)'
    fi
    case "${status}" in
      SUCCESS)
        echo "Build ${build_id} succeeded." >&2
        return 0
        ;;
      FAILURE|INTERNAL_ERROR|TIMEOUT|CANCELLED|EXPIRED)
        echo "Build ${build_id} ended with status ${status}:" \
          "$(log_url "${build_id}")" >&2
        exit 1
        ;;
      QUEUED|PENDING|WORKING)
        failed_checks=0
        echo "Build ${build_id} is ${status}; checking again in" \
          "${POLL_SECONDS}s." >&2
        ;;
      *)
        # describe failed, or printed nothing, STATUS_UNKNOWN or a status that
        # this script doesn't know.
        failed_checks=$(( failed_checks + 1 ))
        if (( failed_checks >= MAX_FAILED_CHECKS )); then
          echo "Couldn't read the status of build ${build_id}:" \
            "${status:-(empty)}; giving up after ${failed_checks} failed" \
            "checks in a row." >&2
          exit 1
        fi
        echo "Couldn't read the status of build ${build_id}:" \
          "${status:-(empty)}; retrying in ${POLL_SECONDS}s" \
          "(${failed_checks}/${MAX_FAILED_CHECKS})." >&2
        ;;
    esac
    sleep "${POLL_SECONDS}"
  done
}

(( $# >= 1 )) || usage
subcommand="$1"
shift
case "${subcommand}" in
  start) start_build "$@" ;;
  wait) wait_for_build "$@" ;;
  *) usage ;;
esac
