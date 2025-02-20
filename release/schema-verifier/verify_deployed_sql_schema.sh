#!/bin/bash
# Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

# This script compares the actual schema in a Cloud SQL database with the golden
# schema in the corresponding release. It detects schema changes made outside
# the normal deployment process, e.g., those made during a troubleshooting
# session that were not cleaned up.
#
# Mounted volumes and required files in them:
# - /secrets/cloud_sql_credential.json: Cloud SQL proxy credential
# - /secrets/schema_deployer_credential.dec the schema_deployer user's
#     database login credential.
# - /schema/schema.jar: the jar with the golden schema.

set -e
read -r cloud_sql_instance db_user db_password \
  <<<$(cat /secrets/schema_deployer_credential.dec | awk '{print $1, $2, $3}')

# Unpack the golden schema from schema.jar
unzip -p /schema/schema.jar sql/schema/nomulus.golden.sql \
  > /schema/nomulus.golden.sql

echo "$(date): Connecting to ${cloud_sql_instance}."

# Set up connection to the Cloud SQL instance.
# For now we use Cloud SQL Proxy to set up a SSL tunnel to the Cloud SQL
# instance. This has two drawbacks:
# - It starts a background process, which is an anti-pattern in Docker.
# - The main job needs to wait for a while for the proxy to come up.
# We will research for a better long-term solution.
#
# Other options considered:
# - Connect using Socket Factory in this script.
#   * Drawback: need to manage version and transitive dependencies
#     of the postgres-socket-factory jar.
# - Create a self-contained Java application that connects using socket factory
#   * Drawback: Seems an overkill
trap "pkill cloud_sql_proxy" EXIT
cloud_sql_proxy -instances="${cloud_sql_instance}"=tcp:5432 \
  --credential_file=/secrets/cloud_sql_credential.json &

set +e
# Wait for cloud_sql_proxy to start:
# first sleep 1 second for the process to launch, then loop until port is ready
# or the proxy process dies.
sleep 1
while ! netstat -an | grep ':5432 ' && pgrep cloud_sql_proxy; do sleep 1; done

if ! pgrep cloud_sql_proxy; then
  echo "Cloud SQL Proxy failed to set up connection."
  exit 1
fi

# Download the actual sql schema
PGPASSWORD=${db_password} pg_dump -h localhost -U "${db_user}" \
    -f /schema/nomulus.actual.sql --schema-only --no-owner --no-privileges \
    --exclude-table flyway_schema_history \
    postgres

if [ $? -ne 0 ]; then
  echo "Failed to dump schema."
  exit 1
else
  echo "Schema dumped."
fi


raw_diff=$(diff /schema/nomulus.golden.sql /schema/nomulus.actual.sql)
# Clean up the raw_diff:
# - Remove diff locations (e.g. "5,6c5,6): grep "^[<>]"
# - Remove leading bracket for easier grepping later: sed -e "s/^[<>]\s//g"
# - Remove comments and blank lines: grep -v -E "^--|^$"
# - Remove patterns in allowed_diffs.txt, which are custom Cloud SQL configs we
#   cannot emulate in the golden schema.
effective_diff=$(echo "${raw_diff}" \
                   | grep "^[<>]" | sed -e "s/^[<>]\s//g" \
                   | grep -v -E "^--|^$"  \
                   | grep -v -f /allowed_diffs.txt )

if [[ ${effective_diff} == "" ]]
then
  echo "Golden and actual schemas match."
  exit 0
else
  echo "Golden and actual schemas do not match. Diff is:"
  echo "${raw_diff}"
  exit 1
fi
