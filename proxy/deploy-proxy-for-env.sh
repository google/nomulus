#!/bin/bash

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
