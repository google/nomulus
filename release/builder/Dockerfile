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

# This Dockerfile builds an image that can be used in Google Cloud Build.
# We need the following programs to build the Nomulus app and the proxy:
# 1. Java 8 for compilation.
# 2. Node.js/NPM for JavaScript compilation.
# 3. Google Cloud SDK for generating the WARs.
# 4. Git to manipulate the private and the merged repos.
# 5. Docker to build and push images.
# 6. deployerForSchedulerAndTasks for deploying cloud scheduler and cloud tasks

FROM golang:1.19 as deployCloudSchedulerAndQueueBuilder
WORKDIR /usr/src/deployCloudSchedulerAndQueue
COPY deployCloudSchedulerAndQueue.go ./
COPY go.sum ./
COPY go.mod ./
RUN go build -o /deployCloudSchedulerAndQueue

FROM marketplace.gcr.io/google/ubuntu2404
ENV DEBIAN_FRONTEND=noninteractive LANG=en_US.UTF-8
# Add script for cloud scheduler and cloud tasks deployment
COPY --from=deployCloudSchedulerAndQueueBuilder /deployCloudSchedulerAndQueue /usr/local/bin/deployCloudSchedulerAndQueue
# Add Cloud sql connector
ADD https://dl.google.com/cloudsql/cloud_sql_proxy.linux.amd64 \
    /usr/local/bin/cloud_sql_proxy
RUN chmod +x /usr/local/bin/cloud_sql_proxy

ADD ./build.sh .
RUN ["bash", "./build.sh"]

