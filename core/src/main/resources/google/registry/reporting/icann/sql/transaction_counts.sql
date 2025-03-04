#standardSQL
  -- Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

SELECT
    tld,
    registrar_table.registrar_name AS registrar_name,
    metricName,
    metricValue
FROM (
    SELECT
        tld,
        clientId,
        CASE WHEN field = 'TRANSFER_SUCCESSFUL' THEN 'TRANSFER_GAINING_SUCCESSFUL'
        WHEN field = 'TRANSFER_NACKED' THEN 'TRANSFER_GAINING_NACKED'
        ELSE field
        END AS metricName,
        -- See b/290228682, there are edge cases in which the net_renew would be negative when
        -- a domain is cancelled by superusers during renew grace period. The correct thing
        -- to do is attribute the cancellation to the owning registrar, but that would require
        -- changing the owing registrar of the the corresponding cancellation DomainHistory,
        -- which has cascading effects that we don't want to deal with. As such we simply
        -- floor the number here to zero to prevent any negative value from appearing, which
        -- should have negligible impact as the edge cage happens very rarely, more specifically
        -- when a cancellation happens during grace period by a registrar other than the the
        -- owning one. All the numbers here should be non-negative to pass ICANN validation.
        GREATEST(SUM(amount), 0) AS metricValue
    FROM (
        SELECT
            CASE
                -- Explicit transfer acks (approve) and nacks (reject) are done
                -- by the opposing registrar. Thus, for these specific actions,
                -- we swap the 'history_other_registrar_id' with the
                -- 'history_registrar_id' to properly account for this reversal.
                WHEN (history_type = 'DOMAIN_TRANSFER_APPROVE'
                OR history_type = 'DOMAIN_TRANSFER_REJECT')
                THEN history_other_registrar_id
                ELSE history_registrar_id
            END AS clientId,
            tld,
            report_field AS field,
            report_amount AS amount,
            reporting_time AS reportingTime
            FROM EXTERNAL_QUERY("projects/%PROJECT_ID%/locations/us/connections/%PROJECT_ID%-sql",
            ''' SELECT history_type, history_other_registrar_id, history_registrar_id, domain_repo_id, history_revision_id FROM "DomainHistory";''') AS dh
            JOIN
                EXTERNAL_QUERY("projects/%PROJECT_ID%/locations/us/connections/%PROJECT_ID%-sql",
                '''SELECT domain_repo_id, history_revision_id, reporting_time, tld, report_field, report_amount FROM "DomainTransactionRecord";''') AS dtr
            ON
            dh.domain_repo_id = dtr.domain_repo_id AND dh.history_revision_id = dtr.history_revision_id
        )
    -- Only look at this month's data
    WHERE reportingTime
    BETWEEN TIMESTAMP('%EARLIEST_REPORT_TIME%')
    AND TIMESTAMP('%LATEST_REPORT_TIME%')
    GROUP BY
        tld,
        clientId,
        field ) AS counts_table
JOIN
     EXTERNAL_QUERY("projects/%PROJECT_ID%/locations/us/connections/%PROJECT_ID%-sql",
                '''SELECT registrar_id, registrar_name FROM "Registrar";''') AS registrar_table
ON
    counts_table.clientId = registrar_table.registrar_id
