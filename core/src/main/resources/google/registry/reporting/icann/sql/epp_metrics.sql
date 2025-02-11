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

  -- Query FlowReporter JSON log messages and calculate SRS metrics.

  -- We use ugly regex's over the monthly appengine logs to determine how many
  -- EPP requests we received for each command. For example:
  -- {"commandType":"check"...,"targetIds":["ais.a.how"],
  -- "tld":"","tlds":["a.how"],"icannActivityReportField":"srs-dom-check"}

SELECT
  -- Remove quotation marks from tld fields.
  REGEXP_EXTRACT(tld, '^"(.*)"$') AS tld,
  activityReportField AS metricName,
  COUNT(*) AS count
FROM (
  SELECT
    -- TODO(b/32486667): Replace with JSON.parse() UDF when available for views
    SPLIT(
    REGEXP_EXTRACT(JSON_EXTRACT(json, '$.tlds'), r'^\[(.*)\]$')) AS tlds,
    JSON_EXTRACT_SCALAR(json,
      '$.resourceType') AS resourceType,
    JSON_EXTRACT_SCALAR(json,
      '$.icannActivityReportField') AS activityReportField
  FROM (
    -- For reasons that I don't understand, if I directly select the three columns
    -- from the union, BigQuery complains about column number mismatch, so I have to
    -- make a temporary union table and select on it.
    SELECT
      *
    FROM (
      SELECT
        -- Extract the logged JSON payload.
        REGEXP_EXTRACT(textPayload, r'FLOW-LOG-SIGNATURE-METADATA: (.*)\n?$')
        AS json
      FROM `%PROJECT_ID%.%APPENGINE_LOGS_DATA_SET%.%APP_LOGS_TABLE%*`
      WHERE
        STARTS_WITH(textPayload, "FLOW-LOG-SIGNATURE-METADATA")
        AND _TABLE_SUFFIX BETWEEN '%FIRST_DAY_OF_MONTH%' AND '%LAST_DAY_OF_MONTH%')
    UNION ALL (
      SELECT
        -- Extract the logged JSON payload.
        REGEXP_EXTRACT(jsonPayload.message, r'FLOW-LOG-SIGNATURE-METADATA: (.*)\n?$')
        AS json
      FROM `%PROJECT_ID%.%GKE_LOGS_DATA_SET%.stderr_*`
      WHERE
        STARTS_WITH(jsonPayload.message, "FLOW-LOG-SIGNATURE-METADATA")
        AND _TABLE_SUFFIX BETWEEN '%FIRST_DAY_OF_MONTH%' AND '%LAST_DAY_OF_MONTH%')
  )) AS regexes
JOIN
  -- Unnest the JSON-parsed tlds.
  UNNEST(regexes.tlds) AS tld
-- Exclude cases that can't be tabulated correctly, where activityReportField
-- is null/empty, or TLD is null/empty despite being a domain flow.
WHERE
  activityReportField != ''
  AND (tld != '' OR resourceType != 'domain')
GROUP BY
  tld, metricName
ORDER BY
  tld, metricName
