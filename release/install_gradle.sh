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
# This script should be invoked from the Gradle root. It downloads the
# gradle distribution saved on GCS, and sets Gradle's distribution URL
# to the local copy. This is necessary since when accessing a GCS bucket
# using http, the bucket must have public access, which is forbidden by
# our policy.

set -e

gradle_url=$(grep distributionUrl gradle/wrapper/gradle-wrapper.properties \
  | awk -F = '{print $2}' | sed 's/\\//g')
gradle_bin=$(basename $gradle_url)
gcs_loc="domain-registry-maven-repository/gradle"

gcloud storage cp "gs://${gcs_loc}/${gradle_bin}" .
local_url="file\\\://${PWD}/${gradle_bin}"
sed -i "s#distributionUrl=.*#distributionUrl=${local_url}#" \
  gradle/wrapper/gradle-wrapper.properties
