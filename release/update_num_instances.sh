#!/bin/bash
# Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
# This script updates number of instances of the service running on GCP
# Required parameters are:
# 1) projectId
# 2) service name
#
# Example:
# ./update_num_instances.sh domain-registry-sandbox pubapi
set -e
project=$1
service=$2
[[ -z "$1" || -z "$2" ]] && { echo "2 parameters required - projectId and service" ; exit 1; }
echo "Project: $project";
echo "Service: $service";

deployed_version=$(gcloud app versions list --service "${service}" \
    --project "${project}" \
    --filter "TRAFFIC_SPLIT>0.00" \
    --format="csv[no-heading](VERSION.ID)")

service_description=$(curl -H "Authorization: Bearer $(gcloud auth print-access-token)" https://appengine.googleapis.com/v1/apps/${project}/services/${service}/versions/${deployed_version})
echo "Service configuration: $service_description"

echo "Input new number of instances: "

read num_instances

if [[ -n ${num_instances//[0-9]/} ]]; then
    echo "Should be an integer"
    exit 1;
fi

echo "Settings new number of instances: $num_instances"

curl -X PATCH https://appengine.googleapis.com/v1/apps/${project}/services/${service}/versions/${deployed_version}?updateMask=manualScaling.instances \
    -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    -H 'Content-Type: application/json' \
    -d "{ \"manualScaling\": { \"instances\": $num_instances }}"

service_description=$(curl -H "Authorization: Bearer $(gcloud auth print-access-token)" https://appengine.googleapis.com/v1/apps/${project}/services/${service}/versions/${deployed_version})
echo "Updated service configuration: $service_description"
