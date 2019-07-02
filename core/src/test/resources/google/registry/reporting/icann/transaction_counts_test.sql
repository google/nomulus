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

  -- Counts the number of mutating transactions each registrar made.

  -- We populate the fields through explicit logging of
  -- DomainTransactionRecords, which contain all necessary information for
  -- reporting (such as reporting time, report field, report amount, etc.

  -- A special note on transfers: we only record 'TRANSFER_SUCCESSFUL' or
  -- 'TRANSFER_NACKED', and we can infer the gaining and losing parties
  -- from the enclosing HistoryEntry's clientId and otherClientId
  -- respectively. This query templates the client ID, field for transfer
  -- success, field for transfer nacks and default field. This allows us to
  -- create one query for TRANSFER_GAINING and the other report fields,
  -- and one query for TRANSFER_LOSING fields from the same template.

-- This outer select just converts the registrar's clientId to their name.
SELECT
  tld,
  registrar_table.registrarName AS registrar_name,
  metricName,
  metricValue
FROM (
  SELECT
    tld,
    clientId,
    CASE
      WHEN field = 'TRANSFER_SUCCESSFUL' THEN 'TRANSFER_GAINING_SUCCESSFUL'
      WHEN field = 'TRANSFER_NACKED' THEN 'TRANSFER_GAINING_NACKED'
      ELSE field
    END AS metricName,
    SUM(amount) AS metricValue
  FROM (
    SELECT
      CASE
        -- Explicit transfer acks (approve) and nacks (reject) are done
        -- by the opposing registrar. Thus, for these specific actions,
        -- we swap the 'otherClientId' with the 'clientId' to properly
        -- account for this reversal.
        WHEN (entries.type = 'DOMAIN_TRANSFER_APPROVE'
          OR entries.type = 'DOMAIN_TRANSFER_REJECT')
          THEN entries.otherClientId
        ELSE entries.clientId
      END AS clientId,
      entries.domainTransactionRecords.tld[SAFE_OFFSET(index)] AS tld,
      entries.domainTransactionRecords.reportingTime[SAFE_OFFSET(index)]
          AS reportingTime,
      entries.domainTransactionRecords.reportField[SAFE_OFFSET(index)]
          AS field,
      entries.domainTransactionRecords.reportAmount[SAFE_OFFSET(index)]
          AS amount
    FROM
      `domain-registry-alpha.latest_datastore_export.HistoryEntry`
          AS entries,
      -- This allows us to 'loop' through the arrays in parallel by index
      UNNEST(GENERATE_ARRAY(0, ARRAY_LENGTH(
        entries.domainTransactionRecords.tld) - 1)) AS index
    -- Ignore null entries
    WHERE entries.domainTransactionRecords IS NOT NULL )
  -- Only look at this month's data
  WHERE reportingTime
  BETWEEN TIMESTAMP('2017-09-01 00:00:00.000')
  AND TIMESTAMP('2017-09-30 23:59:59.999')
  GROUP BY
    tld,
    clientId,
    field ) AS counts_table
JOIN
  `domain-registry-alpha.latest_datastore_export.Registrar`
      AS registrar_table
ON
  counts_table.clientId = registrar_table.__key__.name
