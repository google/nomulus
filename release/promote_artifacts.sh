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
# This script handles post-build promotion and BinAuthz signing:
# 1. Obtains the image digests from the staging repository.
# 2. Promotes the images from staging to gcr.io via the Artifact Registry
#    promoteArtifact API (evaluating BCID exit gate policy and attaching VSA).
# 3. Signs the promoted gcr.io images with Binary Authorization.
#
# Usage:
#   release/promote_artifacts.sh <release_type> <tag_name> <project_id>
# where <release_type> is "nomulus" or "proxy".

set -e

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <nomulus|proxy> <tag_name> <project_id>"
  exit 1
fi

RELEASE_TYPE="$1"
TAG_NAME="$2"
PROJECT_ID="$3"

LOCATION="us"
DEST_REPO="gcr.io"
SRC_REPO="staging"
ENDPOINT="https://artifactregistry.googleapis.com/v1/projects/${PROJECT_ID}/locations/${LOCATION}/repositories/${DEST_REPO}:promoteArtifact"

promote_artifact() {
  local pkg="$1"
  local digest="$2"

  echo "================================================================================"
  echo "Promoting ${pkg}:${TAG_NAME} (${digest}) to ${DEST_REPO} via Artifact Registry"
  echo "================================================================================"

  local token
  token=$(gcloud auth print-access-token)

  local payload
  payload=$(printf '{"source_repository":"projects/%s/locations/%s/repositories/%s","source_version":"projects/%s/locations/%s/repositories/%s/packages/%s/versions/%s","include_all_tags":true,"overwrite_tags":true,"attachment_behavior":"PUBLIC_BCID_VSA_ONLY"}' \
    "${PROJECT_ID}" "${LOCATION}" "${SRC_REPO}" \
    "${PROJECT_ID}" "${LOCATION}" "${SRC_REPO}" "${pkg}" "${digest}")

  local operation_json
  operation_json=$(curl -s --no-progress-meter \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -X POST "${ENDPOINT}" \
    -d "${payload}")

  local operation_name
  if ! operation_name=$(echo "${operation_json}" | python3 -c \
      "import sys, json; d=json.load(sys.stdin); \
       sys.exit(1) if 'error' in d or not d.get('name') else print(d['name'])" \
      2>/dev/null); then
    echo "Failed to initiate promotion for ${pkg}: ${operation_json}"
    exit 1
  fi

  echo "Promotion operation started: ${operation_name}"
  echo "Polling operation status until completion..."

  local max_attempts=5
  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    local status_output
    if ! status_output=$(gcloud artifacts operations describe "${operation_name}" \
        --project="${PROJECT_ID}" \
        --location="${LOCATION}" \
        --format="json" 2>&1) || [[ -z "${status_output}" ]]; then
      echo "Warning: Failed to query operation status; retrying in 5s..."
      if [[ -n "${status_output}" ]]; then
        echo "${status_output}"
      fi
      sleep 5
      continue
    fi
    local status_json="${status_output}"

    local result
    result=$(echo "${status_json}" | python3 -c "import sys, json; d=json.load(sys.stdin); print('IN_PROGRESS' if not d.get('done') else ('ERROR: ' + json.dumps(d['error']) if 'error' in d else 'SUCCESS'))" 2>/dev/null || echo "RETRY")

    if [[ "${result}" == "SUCCESS" ]]; then
      echo "================================================================================"
      echo "Artifact promotion succeeded! BCID VSA attached to ${DEST_REPO}/${pkg}."
      echo "================================================================================"
      return 0
    elif [[ "${result}" =~ ^ERROR: ]]; then
      echo "================================================================================"
      echo "ERROR: Artifact promotion failed BCID policy evaluation or execution for ${pkg}:"
      echo "${result}"
      echo "================================================================================"
      exit 1
    elif [[ "${result}" == "IN_PROGRESS" || "${result}" == "RETRY" ]]; then
      echo "Operation in progress... (attempt ${attempt}/${max_attempts}), retrying in 5s..."
    else
      echo "Warning: Unknown result '${result}'" \
        "(attempt ${attempt}/${max_attempts}), retrying in 5s..."
    fi
    sleep 5
  done

  echo "ERROR: Timed out waiting for promotion operation on ${pkg} to complete."
  exit 1
}

sign_binauthz() {
  local image_name="$1"
  local digest="$2"

  echo "================================================================================"
  echo "Signing ${image_name}@${digest} with Binary Authorization"
  echo "================================================================================"

  gcloud --project="${PROJECT_ID}" beta container binauthz attestations \
    sign-and-create --artifact-url="gcr.io/${PROJECT_ID}/${image_name}@${digest}" \
    --attestor=build-attestor --attestor-project="${PROJECT_ID}" \
    --keyversion-project="${PROJECT_ID}" --keyversion-location=global \
    --keyversion-keyring=attestor-keys --keyversion-key=signing \
    --keyversion=1
}

if [[ "${RELEASE_TYPE}" == "nomulus" ]]; then
  echo "Retrieving digests from staging for nomulus release..."
  nomulus_digest=$(gcloud artifacts docker images describe \
    "us-docker.pkg.dev/${PROJECT_ID}/staging/nomulus:${TAG_NAME}" \
    --format="value(image_summary.digest)")
  proxy_digest=$(gcloud artifacts docker images describe \
    "us-docker.pkg.dev/${PROJECT_ID}/staging/proxy:${TAG_NAME}" \
    --format="value(image_summary.digest)")

  echo "nomulus digest: ${nomulus_digest}"
  echo "proxy digest:   ${proxy_digest}"

  promote_artifact "nomulus" "${nomulus_digest}"
  promote_artifact "proxy" "${proxy_digest}"

  sign_binauthz "nomulus" "${nomulus_digest}"
  sign_binauthz "proxy" "${proxy_digest}"

elif [[ "${RELEASE_TYPE}" == "proxy" ]]; then
  echo "Retrieving digest from staging for proxy release..."
  proxy_digest=$(gcloud artifacts docker images describe \
    "us-docker.pkg.dev/${PROJECT_ID}/staging/proxy:${TAG_NAME}" \
    --format="value(image_summary.digest)")

  echo "proxy digest: ${proxy_digest}"

  promote_artifact "proxy" "${proxy_digest}"
  sign_binauthz "proxy" "${proxy_digest}"
else
  echo "Unknown release type: ${RELEASE_TYPE}"
  exit 1
fi
