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
ARG PROJECT_ID
ARG TAG_NAME
FROM gcr.io/${PROJECT_ID}/builder:${TAG_NAME}

COPY verify_deployed_sql_schema.sh /usr/local/bin/
COPY allowed_diffs.txt /

ENTRYPOINT [ "verify_deployed_sql_schema.sh" ]
