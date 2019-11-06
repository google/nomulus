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
# We need the following programs to build the schema deployer:
# 1. Bash to execute a shell script.
# 2. Java 8 for running the Flywaydb commandline tool.
# 2. Cloud SQL proxy for connection to the SQL instance.
# 3. The Flywaydb commandline tool.
#
# Please refer to deploy_sql_schema.sh for expected volumes and arguments.

# Although any Linux-based Java image with bash would work (e.g., openjdk:8),
# as a GCP application we prefer to start with a GCP-approved base image.
FROM marketplace.gcr.io/google/ubuntu1804
ENV DEBIAN_FRONTEND=noninteractive LANG=en_US.UTF-8
RUN apt-get update -y \
    && apt-get install locales -y \
    && locale-gen en_US.UTF-8 \
    && apt-get install apt-utils -y \
    && apt-get upgrade -y \
    && apt-get install openjdk-8-jdk-headless -y

COPY deploy_sql_schema.sh /usr/local/bin/
ADD https://dl.google.com/cloudsql/cloud_sql_proxy.linux.amd64 \
    /usr/local/bin/cloud_sql_proxy
RUN chmod +x /usr/local/bin/cloud_sql_proxy
# Adapted from https://github.com/flyway/flyway-docker/blob/master/Dockerfile
RUN \
  FLYWAY_MAVEN=https://repo1.maven.org/maven2/org/flywaydb/flyway-commandline \
  && FLYWAY_VERSION=$(curl ${FLYWAY_MAVEN}/maven-metadata.xml \
                       | grep -oP "<release>\K.*(?=</release>)") \
  && echo "Downloading Flyway-commandline-${FLYWAY_VERSION}" \
  && mkdir -p /flyway \
  && curl -L ${FLYWAY_MAVEN}/${FLYWAY_VERSION}/flyway-commandline-${FLYWAY_VERSION}.tar.gz \
     -o flyway-commandline-${FLYWAY_VERSION}.tar.gz \
  && tar -xzf flyway-commandline-${FLYWAY_VERSION}.tar.gz --strip-components=1 \
       -C /flyway \
  && rm flyway-commandline-${FLYWAY_VERSION}.tar.gz

ENTRYPOINT [ "deploy_sql_schema.sh" ]
