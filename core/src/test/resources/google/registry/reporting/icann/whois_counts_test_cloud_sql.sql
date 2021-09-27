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

  -- Query for WHOIS metrics.

  -- This searches the monthly appengine logs for Whois requests, and
  -- counts the number of hits via both endpoints (port 43 and the web).

SELECT
  STRING(NULL) AS tld,
  CASE
    WHEN requestPath = '/_dr/whois' THEN 'whois-43-queries'
    WHEN SUBSTR(requestPath, 0, 7) = '/whois/' THEN 'web-whois-queries'
  END AS metricName,
  COUNT(requestPath) AS count
FROM
  `domain-registry-alpha.cloud_sql_icann_reporting.monthly_logs_201709`
GROUP BY
  metricName
HAVING
  metricName IS NOT NULL
