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

  -- Query for DNS metrics.

  -- You must configure this yourself to enable activity reporting, according
  -- to whatever metrics your DNS provider makes available. We hope to make
  -- this available in the open-source build in the near future.

SELECT
  STRING(NULL) AS tld,
  metricName,
  -1 AS count
FROM ((
  SELECT 'dns-udp-queries' AS metricName)
  UNION ALL
  (SELECT 'dns-tcp-queries' AS metricName))
