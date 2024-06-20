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

  -- Query to fetch AppEngine request logs for the report month.

  -- START_OF_MONTH and END_OF_MONTH should be in YYYYMM01 format.

SELECT
  protoPayload.resource AS requestPath,
  ARRAY(
  SELECT
    logMessage
  FROM
    UNNEST(protoPayload.line)) AS logMessage
FROM
  `domain-registry-alpha.appengine_logs.appengine_googleapis_com_request_log_*`
WHERE
  _TABLE_SUFFIX BETWEEN '20170901' AND '20170930'
