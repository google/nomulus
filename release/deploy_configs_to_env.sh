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
# This script downloads the tagged tarball from GCS and uploads the AppEngine config files therein
# to the provided environment. The standard AppEngine deployment process doesn't automatically
# update these configs, so we must do it manually.

set -e

if [ $# -ne 2 ];
then
  echo "Usage: $0 alpha|crash|sandbox|production <tag_name>"
  exit 1
fi

environment="$1"
tag_name="$2"

if [ "${environment}" == alpha ]; then
  project_id="domain-registry-alpha"
elif [ "${environment}" == crash ]; then
  project_id="domain-registry-crash"
elif [ "${environment}" == sandbox ]; then
  project_id="domain-registry-sandbox"
elif [ "${environment}" == production ]; then
  project_id="domain-registry"
fi

gsutil cp gs://domain-registry-dev-deploy/${tag_name}/${environment}.tar .
tar -xvf ${environment}.tar
for filename in cron dispatch dos index queue; do
  gcloud -q --project ${project_id} app deploy default/WEB-INF/appengine-generated/${filename}.yaml
done
