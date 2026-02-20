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

set -e
apt-get update -y
apt-get upgrade -y

apt-get install locales -y
locale-gen en_US.UTF-8
apt-get install apt-utils gnupg curl lsb-release -y

# Set up Google Cloud SDK repo
# Cribbed from https://cloud.google.com/sdk/docs/quickstart-debian-ubuntu
apt-get install apt-transport-https ca-certificates -y
curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg
echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" |  tee -a /etc/apt/sources.list.d/google-cloud-sdk.list

# Set up PostgreSQL repo. We need pg_dump v11 (same as current server version).
# This needs to be downloaded from postgresql's own repo, because ubuntu2204
# only provides v14.
curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -
echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list

apt-get update -y

# Install GPG2 (in case it was not included)
apt-get install gnupg2 -y

# Install Java
apt-get install openjdk-21-jdk-headless -y

# Install Python
apt-get install python3 -y

# Install Node
apt-get install npm -y
npm cache clean -f
npm install -g n
# Retrying because fails are possible for node.js intallation. See
# https://github.com/nodejs/build/issues/1993
for i in {1..5}; do n 22.12.0 && break || sleep 15; done

# Install gp_dump
apt-get install postgresql-client-17 procps -y

# Install gcloud
apt-get install google-cloud-cli -y
apt-get install google-cloud-sdk-app-engine-java -y
apt-get install kubectl -y
apt-get install google-cloud-cli-gke-gcloud-auth-plugin -y

# Install git
apt-get install git -y

# Install docker
apt-get install docker.io -y

# Install Chrome
apt-get install wget -y
wget https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
apt install ./google-chrome-stable_current_amd64.deb -y

# Install libxss1 (needed by Karma)
apt install libxss1

# Use unzip to extract files from jars.
apt-get install zip -y

# Get netstat, used for checking Cloud SQL proxy readiness.
apt-get install net-tools

# Clean up
apt-get remove apt-utils locales -y
apt-get autoclean -y
apt-get autoremove -y
rm -rf /var/lib/apt/lists/*
