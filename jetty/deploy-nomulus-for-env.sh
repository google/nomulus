#!/bin/bash
# Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
# This script prepares the proxy k8s manifest, pushes it to the clusters, and
# kills all running pods to force k8s to create new pods using the just-pushed
# manifest.

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 alpha|crash|qa"
  exit 1
fi

environment=${1}
project="domain-registry-"${environment}
current_context=$(kubectl config current-context)
while read line
do
  parts=(${line})
  echo "Updating cluster ${parts[0]} in location ${parts[1]}..."
  gcloud container clusters get-credentials "${parts[0]}" \
    --project "${project}" --location "${parts[1]}"
  sed s/GCP_PROJECT/${project}/g "./kubernetes/nomulus-deployment.yaml" | \
  sed s/ENVIRONMENT/${environment}/g | \
  kubectl apply -f -
  kubectl apply -f "./kubernetes/nomulus-service.yaml"
  kubectl apply -f "./kubernetes/nomulus-gateway.yaml"
  # Kills all running pods, new pods created will be pulling the new image.
  kubectl delete pods --all
done < <(gcloud container clusters list --project ${project} | grep nomulus)
kubectl config use-context "$current_context"
