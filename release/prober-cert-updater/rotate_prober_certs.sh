#!/bin/bash
# Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

# Rotates SSL certs for prober registars. This script expects the following
# parameters in order:
# - env: The Nomulus environment, production, sandbox, etc.
# - prober_cert: The SSL cert file (.pem) to be installed.
# - prober_list: The probers' registrar-ids in a file, one per line.
# - tools_credential: The credential (.json) needed to run the nomulus command.

set -e
if [ "$#" -ne 4 ]; then
  echo "Expecting four parameters in order: env prober_cert_file prober_list" \
      "tools_credential"
  exit 1
fi

nomulus_env="${1}"
cert_file="${2}"
prober_list="${3}"
tools_credential="${4}"

echo ${nomulus_env} ${cert_file} ${prober_list}

cat "${prober_list}" | while IFS= read -r prober; do
  echo "Updating client certificate for ${prober}."
  java -jar /nomulus.jar -e "${nomulus_env}" \
      --credential "${tools_credential}" \
      update_registrar "${prober}" -f \
      --rotate_primary_cert \
      --cert_file  "${cert_file}"
done
