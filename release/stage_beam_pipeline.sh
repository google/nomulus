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
# This script builds and stages one or more flex-template based BEAM pipelines
# that can share one Uber jar. The following positional parameters are required:
# - uberjar_name: this is the name of a createUberJar task in :core and also the
#   name of the Uber jar created by that task. It is expected to be in the
#   ./core/build/libs folder.
# - release_tag
# - dev_project
# - main_class: the fully qualified main class name of a pipeline.
# - metadata_pathname: the metadata file of the pipeline named by the previous
#   parameter. It is expected to be in the resources folder of :core, and its
#   value should be the relative path from resources.
#
# If successful, this script will generate and upload two artifacts for each
# pipeline:
# - A template file to
#   gs://${dev_project}-deploy/${release_tag}/beam/$(basename metadata_pathname)
# - A docker image to gcs.io/${dev_project}/beam/${pipeline_name}:{release_tag},
#   where ${pipeline_name} is the ${main_class}'s simple name converted to
#   lower_underscore form.
#
# The staged pipelines may be invoked by gcloud or the flex-template launcher's
# REST API.

set -e

if (( "$#" < 6 ||  $(("$#" % 2)) == 1 ));
then
  echo "Usage: $0 uberjar_task uberjar_name release_tag dev_project " \
       "main_class metadata_pathname [ main_class metadata_pathname ] ..."
  exit 1
fi

uberjar_task="$1"
uberjar_name="$2"
release_tag="$3"
dev_project="$4"
shift 4

maven_gcs_prefix="gcs://domain-registry-maven-repository"
nom_build_dir="$(dirname $0)/.."
${nom_build_dir}/nom_build clean :core:"${uberjar_task}" \
    --mavenUrl="${maven_gcs_prefix}"/maven \
    --pluginsUrl="${maven_gcs_prefix}"/plugins

while (( "$#" > 0 )); do
  main_class="$1"; shift
  metadata_pathname="$1"; shift
  # Get main_class' simple name in lower_underscore form
  pipeline_name=$(
      echo "${main_class}" | rev | cut -d. -f1 | rev | \
      sed -r 's/([A-Z])/_\L\1/g' | sed 's/^_//')
  image_name="gcr.io/${dev_project}/beam/${pipeline_name}"
  metadata_basename=$(basename "${metadata_pathname}")

  gcloud dataflow flex-template build \
    "gs://${dev_project}-deploy/${release_tag}/beam/${metadata_basename}" \
    --image-gcr-path "${image_name}:${release_tag}" \
    --sdk-language "JAVA" \
    --flex-template-base-image JAVA11 \
    --metadata-file "./core/src/main/resources/${metadata_pathname}" \
    --jar "./core/build/libs/${uberjar_name}.jar" \
    --env FLEX_TEMPLATE_JAVA_MAIN_CLASS="${main_class}" \
    --project "${dev_project}"
done
