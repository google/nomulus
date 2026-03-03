#!/bin/bash
# Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

# Terraform currently cannot set named ports on the instance groups underlying
# the gke instances it creates. Here we output the instance group URL, extract
# the project, zone and instance group names, and then call gcloud to add the
# named ports.

PROD_PORTS="epp:30002"
CANARY_PORTS="epp-canary:31002"

while read line
do
  gcloud compute instance-groups set-named-ports --named-ports \
    "${PROD_PORTS}","${CANARY_PORTS}" "$line"
done < <(terraform output proxy_instance_groups | awk '{print $3}' | \
  awk -F '/' '{print "--project", $7, "--zone", $9, $11}')
