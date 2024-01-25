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

# Sync the configuration files in the internal repo with the objects in the
# database. Loops through the configuration files in the inputted directory and
# runs the passed in nomulus update command with the file.

# - env: The Nomulus environment, production, sandbox, etc.
# - tools_credential: The credential (.json) needed to run the nomulus command.
# - nomulus_command: The nomulus command to run.
# - config_file_directory: The internal directory storing the TLD config files.

set -e
if [ "$#" -ne 4 ]; then
  echo "Expecting four parameters in order: env tools_credential nomulus_command config_file_directory"
  exit 1
fi

nomulus_env="${1}"
tools_credential="${2}"
nomulus_command="${3}"
config_file_directory="${4}"

echo ${config_file_directory}

for FILE in ${config_file_directory}/${nomulus_env}/*; do
  echo $FILE
  java -jar /nomulus.jar -e "${nomulus_env}" \
  --credential "${tools_credential}" \
  "${nomulus_command}" -i $FILE --force --build_environment
done
