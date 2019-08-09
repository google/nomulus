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

if [[ $# -ne 1 ]]; then
 echo "Usage: $0 alpha|crash"
 exit 1
fi

environment=${1}
project="domain-registry-"${environment}
current_context=$(kubectl config current-context)
while read line
do
  parts=(${line})
  echo "Updating cluster ${parts[0]} in zone ${parts[1]}..."
  gcloud container clusters get-credentials "${parts[0]}" \
    --project "${project}" --zone "${parts[1]}"
  # Kills all running pods, new pods created will be pulling the new image.
  sed s/GCP_PROJECT/${project}/g "./kubernetes/proxy-deployment-${environment}.yaml" | \
  kubectl replace -f -
  # Alpha does not have canary
  if [[ ${environment} != "alpha" ]]
  then
  sed s/GCP_PROJECT/${project}/g "./kubernetes/proxy-deployment-${environment}-canary.yaml" | \
  kubectl replace -f -
  fi
  kubectl delete pods --all
done < <(gcloud container clusters list --project ${project} | grep proxy-cluster)
kubectl config use-context "$current_context"
