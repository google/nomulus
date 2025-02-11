#standardSQL
  -- Copyright 2017 The Nomulus Authors. All Rights Reserved.
  --
  -- Licensed under the Apache License, Version 2.0 (the "License");
  -- you may not use this file except in compliance with the License.
  -- You may obtain a copy of the License at
  --
  --     http://www.apache.org/licenses/LICENSE-2.0
  --
  -- Unless required by applicable law or agreed to in writing, software
  -- distributed under the License is distributed on an "AS IS" BASIS,
  -- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  -- See the License for the specific language governing permissions and
  -- limitations under the License.

  -- Query to fetch AppEngine and GKE request logs for the report month.

  -- START_OF_MONTH and END_OF_MONTH should be in YYYYMM01 format.

SELECT
  *
FROM (
  SELECT
    jsonPayload.httrequest.requesturl AS requestPath
  FROM
    `%PROJECT_ID%.%GKE_LOGS_DATA_SET%.stderr_*`
  WHERE
    _TABLE_SUFFIX BETWEEN '%FIRST_DAY_OF_MONTH%' AND '%LAST_DAY_OF_MONTH%')
UNION ALL (
  SELECT
    protoPayload.resource AS requestPath
  FROM
    `%PROJECT_ID%.%APPENGINE_LOGS_DATA_SET%.%REQUEST_TABLE%*`
  WHERE
    _TABLE_SUFFIX BETWEEN '%FIRST_DAY_OF_MONTH%' AND '%LAST_DAY_OF_MONTH%')
