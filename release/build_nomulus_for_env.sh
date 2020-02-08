#!/bin/bash
# Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
# This script builds the GAE artifacts for a given environment, moves the
# artifacts for all services to a designated location, and then creates a
# tarball from there.

set -e

if [ $# -ne 2 ];
then
  echo "Usage: $0 alpha|crash|sandbox|production|tool <destination>"
  exit 1
fi

environment="$1"
dest="$2"
gcs_prefix="storage.googleapis.com/domain-registry-maven-repository"

# Let Gradle put its caches (dependency cache and build cache) in the source
# tree. This allows sharing of the caches between steps in a Cloud Build
# task. (See ./cloudbuild-nomulus.yaml, which calls this script in several
# steps). If left at their default location, the caches will be lost after
# each step.
export GRADLE_USER_HOME="./cloudbuild-caches"

if [ "${environment}" == tool ]
then
  mkdir -p "${dest}"

  ./gradlew clean :core:buildToolImage \
    -PmavenUrl=https://"${gcs_prefix}"/maven \
    -PpluginsUrl=https://"${gcs_prefix}"/plugins

  mv core/build/libs/nomulus.jar "${dest}"
else
  dest="${dest}/$1"
  mkdir -p "${dest}"

  ./gradlew clean stage -Penvironment="${environment}" \
    -PmavenUrl=https://"${gcs_prefix}"/maven \
    -PpluginsUrl=https://"${gcs_prefix}"/plugins

  for service in default pubapi backend tools
  do
    mv services/"${service}"/build/staged-app "${dest}/${service}"
  done

  mv core/build/resources/main/google/registry/env/common/META-INF \
    "${dest}/META-INF"

  cd "${dest}"
  tar cvf ../"${environment}.tar" .
  cd -
  rm -rf "${dest}"
fi
