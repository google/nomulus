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
# This script prepares the source tree of a release image build. It adds the
# internal overlay, and pins the Dockerfiles by digest to the copies of the
# base images that release/cloudbuild-release.yaml saves for each release.
#
# The builds that start from this repository at a release tag,
# release/cloudbuild-nomulus-images.yaml and release/cloudbuild-proxy-images.yaml,
# prepare their tree with it, the same way release/cloudbuild-release.yaml
# prepares the tree it uploads. Keep the two in sync.
#
# Usage, from anywhere in the tree (the script works on the tree's root):
#   prepare_release_tree.sh clone-internal <repo_url> <tag>
#     Clones the internal repository at <tag> into nomulus-internal/.
#   prepare_release_tree.sh merge-internal
#     Copies nomulus-internal/ over the tree, then deletes it and .git/.
#   prepare_release_tree.sh pin-base-images <project_id> [--temurin=@<digest>]
#       [--jetty=@<digest>] <component>...
#     Pins <component>/Dockerfile (core, jetty or proxy) to the release's copy
#     of its base image: gcr.io/<project_id>/temurin@<digest> for core and
#     proxy, gcr.io/<project_id>/jetty@<digest> for jetty. A <digest> is
#     sha256: and 64 lowercase hex digits. Checks every component before it
#     edits any Dockerfile, and logs each pin.
#   prepare_release_tree.sh set-proxy-project <project_id>
#     Sets the project in the proxy's Kubernetes manifests.

set -e

# The base images that the Dockerfiles name. Keep them in sync with the
# Dockerfiles.
readonly TEMURIN_BASE_IMAGE='eclipse-temurin:25'
readonly JETTY_BASE_IMAGE='jetty:12-jdk25'

usage() {
  cat >&2 <<EOF
Usage:
  $0 clone-internal <repo_url> <tag>
  $0 merge-internal
  $0 pin-base-images <project_id> [--temurin=@<digest>] [--jetty=@<digest>] <component>...
  $0 set-proxy-project <project_id>
EOF
  exit 1
}

clone_internal() {
  (( $# == 2 )) || usage
  local repo_url="$1" tag="$2" tools
  # git-cookie-authdaemon gives git the build's credentials for
  # googlesource.com. It's cloned outside the tree to keep it out of the build.
  tools="$(mktemp -d)"
  git clone https://gerrit.googlesource.com/gcompute-tools "${tools}"
  sed -i 's@/usr/bin/python@/usr/bin/python3@g' "${tools}/git-cookie-authdaemon"
  "${tools}/git-cookie-authdaemon"
  git clone --depth 1 --branch "${tag}" "${repo_url}" nomulus-internal
  echo "nomulus-internal ${tag} at $(git -C nomulus-internal rev-parse HEAD)"
}

merge_internal() {
  (( $# == 0 )) || usage
  shopt -s dotglob
  rm -rf .git nomulus-internal/.git
  cp -rf nomulus-internal/* .
  rm -rf nomulus-internal
}

pin_base_images() {
  (( $# >= 2 )) || usage
  local project_id="$1" temurin_ref='' jetty_ref='' arg
  local -a components=()
  shift
  for arg in "$@"; do
    case "${arg}" in
      --temurin=*) temurin_ref="${arg#--temurin=}" ;;
      --jetty=*) jetty_ref="${arg#--jetty=}" ;;
      -*) usage ;;
      *) components+=("${arg}") ;;
    esac
  done
  (( ${#components[@]} > 0 )) || usage
  # Check every component before editing any Dockerfile.
  local component base_image copy ref
  local -a dockerfiles=() base_images=() pinned_images=()
  for component in "${components[@]}"; do
    case "${component}" in
      core|proxy) base_image="${TEMURIN_BASE_IMAGE}"; copy=temurin; ref="${temurin_ref}" ;;
      jetty) base_image="${JETTY_BASE_IMAGE}"; copy=jetty; ref="${jetty_ref}" ;;
      *) echo "Unknown component: ${component}" >&2; exit 1 ;;
    esac
    # A lookup that finds no image with the release's tag leaves just the @.
    if [[ ! "${ref}" =~ ^@sha256:[0-9a-f]{64}$ ]]; then
      echo "${component} needs --${copy}=@sha256:<64 hex digits>, not '${ref}'." >&2
      exit 1
    fi
    # Fail rather than leave the Dockerfile on the public base image.
    if ! grep -qF "${base_image}" "${component}/Dockerfile"; then
      echo "${component}/Dockerfile doesn't use ${base_image}; update $0." >&2
      exit 1
    fi
    dockerfiles+=("${component}/Dockerfile")
    base_images+=("${base_image}")
    pinned_images+=("gcr.io/${project_id}/${copy}${ref}")
  done
  local i
  for i in "${!dockerfiles[@]}"; do
    sed -i "s%${base_images[i]}%${pinned_images[i]}%g" "${dockerfiles[i]}"
    echo "${dockerfiles[i]}: ${base_images[i]} -> ${pinned_images[i]}"
  done
}

set_proxy_project() {
  (( $# == 1 )) || usage
  sed -i "s/GCP_PROJECT/$1/" proxy/kubernetes/proxy-*.yaml
}

(( $# >= 1 )) || usage
subcommand="$1"
shift
cd "$(dirname "$0")/.."
case "${subcommand}" in
  clone-internal) clone_internal "$@" ;;
  merge-internal) merge_internal "$@" ;;
  pin-base-images) pin_base_images "$@" ;;
  set-proxy-project) set_proxy_project "$@" ;;
  *) usage ;;
esac
