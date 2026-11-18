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
# Unit tests for release/prepare_release_tree.sh.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
readonly REPO_ROOT
readonly HEX="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
readonly DIGEST="sha256:${HEX}"
readonly PROJECT="domain-registry-dev"
readonly BASE_IMAGE="launcher.gcr.io/google/ubuntu2404"
readonly EXPECTED_PIN="FROM gcr.io/${PROJECT}/ubuntu2404@${DIGEST}"

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

stage_tree() {
  local dir="$1" comp
  mkdir -p "${dir}/release" "${dir}/core" "${dir}/proxy/kubernetes" \
    "${dir}/jetty"
  cp "${REPO_ROOT}/release/prepare_release_tree.sh" "${dir}/release/"
  for comp in core proxy jetty; do
    if grep -qE '^FROM gcr\.io/.+/ubuntu2404@sha256:[0-9a-f]{64}$' \
        "${REPO_ROOT}/${comp}/Dockerfile"; then
      sed -E "s%^FROM gcr\\.io/.+%FROM ${BASE_IMAGE}%" \
        "${REPO_ROOT}/${comp}/Dockerfile" > "${dir}/${comp}/Dockerfile"
    else
      cp "${REPO_ROOT}/${comp}/Dockerfile" "${dir}/${comp}/Dockerfile"
    fi
  done
  echo "image: gcr.io/GCP_PROJECT/proxy" \
    > "${dir}/proxy/kubernetes/proxy-deployment-alpha.yaml"
}

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

# 1. Happy path: pin core, proxy, and jetty Dockerfiles.
tree1="${TMP_ROOT}/happy"
stage_tree "${tree1}"
bash "${tree1}/release/prepare_release_tree.sh" pin-base-images "${PROJECT}" \
  --ubuntu="@${DIGEST}" core proxy jetty >/dev/null
for comp in core proxy jetty; do
  grep -q "^${EXPECTED_PIN}$" "${tree1}/${comp}/Dockerfile" \
    || fail "${comp}/Dockerfile was not pinned to ${EXPECTED_PIN}"
done

# 2. Fail-closed checks: bad digest, unknown component, and mismatched FROM
# must all exit non-zero without editing earlier valid Dockerfiles.
tree2="${TMP_ROOT}/errors"
stage_tree "${tree2}"
cp "${tree2}/core/Dockerfile" "${TMP_ROOT}/core.orig"
if bash "${tree2}/release/prepare_release_tree.sh" pin-base-images \
    "${PROJECT}" --ubuntu="@" core 2>/dev/null; then
  fail "Expected empty digest lookup (@) to fail"
fi
if bash "${tree2}/release/prepare_release_tree.sh" pin-base-images \
    "${PROJECT}" --ubuntu="@sha256:XYZ" core 2>/dev/null; then
  fail "Expected malformed digest to fail"
fi
if bash "${tree2}/release/prepare_release_tree.sh" pin-base-images \
    "${PROJECT}" --ubuntu="@${DIGEST}" core unknown 2>/dev/null; then
  fail "Expected unknown component to fail"
fi
cmp -s "${TMP_ROOT}/core.orig" "${tree2}/core/Dockerfile" \
  || fail "core/Dockerfile was modified despite unknown sibling component"

echo "FROM other:latest" > "${tree2}/jetty/Dockerfile"
if bash "${tree2}/release/prepare_release_tree.sh" pin-base-images \
    "${PROJECT}" --ubuntu="@${DIGEST}" core jetty 2>/dev/null; then
  fail "Expected mismatched base image in jetty/Dockerfile to fail"
fi
cmp -s "${TMP_ROOT}/core.orig" "${tree2}/core/Dockerfile" \
  || fail "core/Dockerfile was modified despite mismatched jetty/Dockerfile"

# 3. set-proxy-project replaces GCP_PROJECT in proxy manifests.
bash "${tree1}/release/prepare_release_tree.sh" set-proxy-project "${PROJECT}"
grep -q "gcr.io/${PROJECT}/proxy" \
  "${tree1}/proxy/kubernetes/proxy-deployment-alpha.yaml" \
  || fail "set-proxy-project did not replace GCP_PROJECT"
