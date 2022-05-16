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

# This Dockerfile builds an image that can be used in Google Cloud Build.
# We need the following programs to build the schema verifier:
# 1. Bash to execute a shell script.
# 2. Cloud SQL proxy for connection to the SQL instance.
# 3. The pg_dump tool.
# 4. The unzip command to extract the golden schema from the schema jar.
#
# Please refer to verify_deployed_sql_schema.sh for expected volumes and
# arguments.

FROM marketplace.gcr.io/google/ubuntu1804
ENV DEBIAN_FRONTEND=noninteractive LANG=en_US.UTF-8
# Install pg_dump v11 (same as current server version). This needs to be
# downloaded from postgresql's own repo, because ubuntu1804 is too old. With a
# newer image 'apt-get install postgresql-client-11' may be sufficient.
RUN apt-get update -y \
    && apt-get install locales -y \
    && locale-gen en_US.UTF-8 \
    && apt-get install curl gnupg lsb-release -y \
    && curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add - \
    && sh -c \
           'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
           > /etc/apt/sources.list.d/pgdg.list' \
    && apt-get update -y \
    && apt install postgresql-client-11 -y

# Use unzip to extract files from jars.
RUN apt-get install zip -y

# Get netstat, used for checking Cloud SQL proxy readiness.
RUN apt-get install net-tools

COPY verify_deployed_sql_schema.sh /usr/local/bin/
COPY allowed_diffs.txt /

ADD https://dl.google.com/cloudsql/cloud_sql_proxy.linux.amd64 \
    /usr/local/bin/cloud_sql_proxy
RUN chmod +x /usr/local/bin/cloud_sql_proxy

ENTRYPOINT [ "verify_deployed_sql_schema.sh" ]
