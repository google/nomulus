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

# Create the instances - modify this number for the amount of instances you
# would like to use for your load test
gcloud compute instances create loadtest-{1..2} --machine-type g1-small \
--image-family ubuntu-2204-lts --image-project ubuntu-os-cloud --zone us-east4-a

sleep 10

# Get all the created load tests instances
HOSTS=$(gcloud compute instances list | awk '/^loadtest/ { print $5 }')

#Install rsync and Java - Retry is needed here since ssh connection will fail until instances are fully provisioned
for host in $HOSTS;
  do
    for i in {1..60}; do
      if ssh $host 'sudo apt-get -y update &&
                    sudo apt-get -y upgrade &&
                    sudo apt-get -y install rsync &&
                    sudo apt-get -y install openjdk-21-jdk'; then
        break
      else
        sleep 5
      fi
    done
  done
