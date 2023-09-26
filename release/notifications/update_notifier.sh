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
# This script redeploys the Cloud Build notifier with the latest container
# image. It can be used to force immediate configuration change after invoking
# `upload_config.sh`.

set -e

if [ $# -ne 1 ];
then
  echo "Usage: $0 <project_id>"
  exit 1
fi

project_id="$1"

region=$(gcloud run services list \
         --filter="SERVICE:googlechat-notifier" \
         --format="csv[no-heading](REGION)" \
         --project="${project_id}")

SERVICE_NAME=googlechat-notifier
IMAGE_PATH=us-east1-docker.pkg.dev/gcb-release/cloud-build-notifiers/googlechat:latest
DESTINATION_CONFIG_PATH="gs://${project_id}-notifiers-config/googlechat.yaml"

gcloud run deploy "${SERVICE_NAME}" --image="${IMAGE_PATH}" \
    --no-allow-unauthenticated  \
    --update-env-vars="CONFIG_PATH=${DESTINATION_CONFIG_PATH},PROJECT_ID=${project_id}" \
    --region="${region}" \
    --project="${project_id}" \
    || fail "failed to deploy notifier service -- check service logs for configuration error"
