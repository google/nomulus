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

# Mounted volumes and required files in them:
# - /secrets
#   * cloud_sql_credential.json: Cloud SQL proxy credential
# - /flyway/jars: the schema jar to be deployed.
#
# Database login info may be passed in two ways:
# - Decrypt the admin_credential.enc file on GCS and map it as
#   /secrets/admin_credential.dec
# - Provide the content of the admin_credential as command line arguments

set -e
if [ "$#" -le 1 ]; then
  if [ ! -f /secrets/admin_credential.dec ]; then
    echo "Missing /secrets/admin_credential.dec"
    exit 1
  fi
  cloud_sql_instance=$(cut -d' ' -f1 /secrets/admin_credential.dec)
  db_user=$(cut -d' ' -f2 /secrets/admin_credential.dec)
  db_password=$(cut -d' ' -f3 /secrets/admin_credential.dec)
  flyway_action=${1:-validate}
elif [ "$#" -ge 3 ]; then
  cloud_sql_instance=$1
  db_user=$2
  db_password=$3
  flyway_action=${4:-validate}
else
  echo "Wrong number of arguments."
  exit 1
fi

# Disallow the 'clean' command, which drops the entire database.
# See https://flywaydb.org/documentation/commandline/ for command listing.
if [ "${flyway_action}" == "clean" ]; then
  echo "The clean action is not allowed."
  exit 1;
fi
echo "$(date): Starting ${flyway_action} action on ${cloud_sql_instance}."

# Set up connection to the Cloud SQL instance.
# For now we use Cloud SQL Proxy to set up a SSL tunnel to the Cloud SQL
# instance. This only a temporary solution since it has two drawbacks:
# - It starts a background process, which is an anti-pattern in Docker.
# - The main job needs to wait for a while for the proxy to come up.
# The long-term plan is to move our Cloud SQL instances to private network,
# and use private IP addresses for JDBC connection.
#
# Other options considered:
# - Connect using Socket Factory in this script.
#   * Drawback: need to manage version and transitive dependencies
#     of the postgres-socket-factory jar.
# - Create a self-contained Java application that connects using socket factory
#   * Drawback: Seems an overkill
cloud_sql_proxy -instances=${cloud_sql_instance}=tcp:5432 \
  --credential_file=/secrets/cloud_sql_credential.json &
SQL_PROXY_PID=$!
# Wait for cloud_sql_proxy to start.
while ! netstat -an | grep 5432; do sleep 1; done

set +e
/flyway/flyway -community -user=${db_user} -password=${db_password} \
  -url=jdbc:postgresql://localhost:5432/postgres \
  -locations=classpath:sql/flyway \
  ${flyway_action}
migration_result=$?

if [ ${flyway_action} == "migrate" ]; then
  # After deployment, log the current schema.
  /flyway/flyway -community -user=${db_user} -password=${db_password} \
    -url=jdbc:postgresql://localhost:5432/postgres \
    -locations=classpath:sql/flyway \
    info
fi
# Stop Cloud SQL Proxy
kill -1 ${SQL_PROXY_PID}
exit ${migration_result}
