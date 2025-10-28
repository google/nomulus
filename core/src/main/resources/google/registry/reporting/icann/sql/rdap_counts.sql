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

  -- Query for RDAP metrics.
  -- This searches the monthly GKE logs for RDAP requests and counts the number of hits

SELECT
  STRING(NULL) AS tld,
  'rdap-queries' AS metricName,
  count(*)
FROM `%PROJECT_ID%.%ICANN_REPORTING_DATA_SET%.%MONTHLY_LOGS_TABLE%`
WHERE SUBSTR(requestPath, 0, 6) = '/rdap/'
