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
apt-get install locales -y
locale-gen en_US.UTF-8
apt-get install apt-utils gnupg -y
apt-get upgrade -y
# Install Java
apt-get install openjdk-11-jdk-headless -y
# Install nvm
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.35.3/install.sh | bash
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
[ -s "$NVM_DIR/bash_completion" ] && \. "$NVM_DIR/bash_completion"
# Install node
nvm install node
# Install npm
nvm install-latest-npm
# Install gcloud
# Cribbed from https://cloud.google.com/sdk/docs/quickstart-debian-ubuntu
apt-get install lsb-release -y
export CLOUD_SDK_REPO="cloud-sdk-$(lsb_release -c -s)"
echo "deb http://packages.cloud.google.com/apt $CLOUD_SDK_REPO main" \
  | tee -a /etc/apt/sources.list.d/google-cloud-sdk.list
curl https://packages.cloud.google.com/apt/doc/apt-key.gpg \
  | apt-key add -
apt-get update -y
apt-get install google-cloud-sdk-app-engine-java -y
# Install git
apt-get install git -y
# Install docker
apt-get install docker.io -y
# Install Chrome
apt-get install wget -y
wget https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
apt install ./google-chrome-stable_current_amd64.deb -y
apt-get remove apt-utils locales -y
apt-get autoclean -y
apt-get autoremove -y
rm -rf /var/lib/apt/lists/*
