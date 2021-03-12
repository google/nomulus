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
# This script builds and stages a flex-template based BEAM pipeline. The
# following parameters are required:
# - pipeline_name: this is also the name of a createUberJar task in :core and
#   the name of the jar file created by that task.
# - main_class: the pipeline's main class name.
# - metadata_pathname: the pipeline's metadata file, which is in the resources
#   folder of :core. This parameter should be the relative path from resources.
# - release_tag
# - dev_project
#
# If successful, this script will generate and upload two artifacts:
# - A template file to
#   gs://${dev_project}-deploy/${release_tag}/beam/$(basename metadata_pathname)
# - A docker image to gcs.io/${dev_project}/beam/${pipeline_name}:{release_tag}
#
# Please refer to gcloud documentation for how to start the pipeline.

set -e

if [ $# -ne 5 ];
then
  echo "Usage: $0 pipeline_name main_class metadata_pathname release_tag" \
       "dev_project"
  exit 1
fi

pipeline_name="$1"
main_class="$2"
metadata_pathname="$3"
release_tag="$4"
dev_project="$5"

image_name="gcr.io/${dev_project}/beam/${pipeline_name}"
metadata_basename=$(basename ${metadata_pathname})

gcs_prefix="gcs://domain-registry-maven-repository"

./gradlew clean :core:"${pipeline_name}" \
    -PmavenUrl="${gcs_prefix}"/maven \
    -PpluginsUrl="${gcs_prefix}"/plugins

gcloud dataflow flex-template build \
    "gs://${dev_project}-deploy/${release_tag}/beam/${metadata_basename}" \
    --image-gcr-path "${image_name}:${release_tag}" \
    --sdk-language "JAVA" \
    --flex-template-base-image JAVA11 \
    --metadata-file "./core/src/main/resources/${metadata_pathname}" \
    --jar "./core/build/libs/${pipeline_name}.jar" \
    --env FLEX_TEMPLATE_JAVA_MAIN_CLASS="${main_class}" \
    --project ${dev_project}
