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
# This script uploads the `googlechat.yaml` configuration to the GCS bucket used
# by the Cloud Build notifier. The new config takes effect only AFTER all
# currently running notifier instances are shut down due to inactivity. To force
# immediate change, use `update_notifier.sh` in this directory to redeploy the
# service.

set -e

if [ $# -ne 1 ];
then
  echo "Usage: $0 <project_id>"
  exit 1
fi

project_id="$1"

SCRIPT_DIR="$(realpath $(dirname $0))"

cat "${SCRIPT_DIR}"/googlechat.yaml  \
    | sed  "s/_project_id_/${project_id}/g" \
    | gcloud storage cp - "gs://${project_id}-notifiers-config/googlechat.yaml"
