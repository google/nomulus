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

if [ $# -ne 2 ];
then
  echo "Usage: $0 check|apply <glob>"
  exit 1
fi

create_formatted_file () {
  sql_file="$1"
  ../node_modules/sql-formatter-cli/dist/sql-formatter-cli -i ${sql_file} -o ${sql_file}.formatted
  echo "" >> ${sql_file}.formatted # newline at the end
}

VERB=$1
GLOB=$2

failed_files=""
for sql_file in $(find ${GLOB} -type f -name "*.sql"); do
  create_formatted_file ${sql_file}
  if [ "$1" = "apply" ]; then
    # Note: the formatter isn't necessarily idempotent -- the output of the first run might
    # be different from the output of running it again. Run it a few times so it stabilizes.
    for i in {1..10}; do
      mv "${sql_file}.formatted" "${sql_file}"
      create_formatted_file ${sql_file}
    done
  else
    if ! cmp -s "${sql_file}" "${sql_file}.formatted"; then
      failed_files="${failed_files} ${sql_file}"
    fi
  fi
  rm "${sql_file}.formatted"
done
if [ ! -z "$failed_files" ]; then
  echo "Files should be formatted; use the db:sqlFormatApply task or run 'db/format.sh apply'"
  for file in $failed_files; do
    echo $file
  done
  exit 1
fi
