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

# Abort on error.
set -e

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 alpha|crash|qa [base_domain]}"
  exit 1
fi

environment=${1}
base_domain=${2}
project="domain-registry-"${environment}
current_context=$(kubectl config current-context)
line=$(gcloud container clusters list --project "${project}" | grep nomulus | grep main)
parts=(${line})
echo "Updating cluster ${parts[0]} in location ${parts[1]}..."
gcloud container fleet memberships get-credentials "${parts[0]}" --project "${project}"
for service in frontend backend pubapi console
do
  sed s/GCP_PROJECT/"${project}"/g "./kubernetes/nomulus-${service}.yaml" | \
  sed s/ENVIRONMENT/"${environment}"/g | \
  sed s/PROXY_ENV/"${environment}"/g | \
  sed s/EPP/"epp"/g | \
  kubectl apply -f -
  kubectl rollout restart deployment/${service}
  # canary
  sed s/GCP_PROJECT/"${project}"/g "./kubernetes/nomulus-${service}.yaml" | \
  sed s/ENVIRONMENT/"${environment}"/g | \
  sed s/PROXY_ENV/"${environment}_canary"/g | \
  sed s/EPP/"epp-canary"/g | \
  sed s/"${service}"/"${service}-canary"/g | \
  kubectl apply -f -
  kubectl rollout restart deployment/${service}-canary
done
kubectl apply -f "./kubernetes/gateway/nomulus-gateway.yaml"
kubectl apply -f "./kubernetes/gateway/nomulus-iap-${environment}.yaml"
for service in frontend backend console pubapi
do
  sed s/BASE_DOMAIN/"${base_domain}"/g "./kubernetes/gateway/nomulus-route-${service}.yaml" | \
  kubectl apply -f -
  sed s/SERVICE/"${service}"/g "./kubernetes/gateway/nomulus-backend-policy-${environment}.yaml" | \
  kubectl apply -f -
  sed s/SERVICE/"${service}-canary"/g "./kubernetes/gateway/nomulus-backend-policy-${environment}.yaml" | \
  kubectl apply -f -
done

kubectl config use-context "$current_context"
