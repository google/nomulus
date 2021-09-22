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

  -- This query pulls from all intermediary tables to create the activity
  -- report csv, via a table transpose and sum over all activity report fields.

SELECT
  RealTlds.tld AS tld,
  SUM(IF(metricName = 'operational-registrars', count, 0)) AS operational_registrars,
  -- We use the Centralized Zone Data Service.
  "CZDS" AS zfa_passwords,
  SUM(IF(metricName = 'whois-43-queries', count, 0)) AS whois_43_queries,
  SUM(IF(metricName = 'web-whois-queries', count, 0)) AS web_whois_queries,
  -- We don't support searchable WHOIS.
  0 AS searchable_whois_queries,
  -- DNS queries for UDP/TCP are all assumed to be received/responded.
  SUM(IF(metricName = 'dns-udp-queries', count, 0)) AS dns_udp_queries_received,
  SUM(IF(metricName = 'dns-udp-queries', count, 0)) AS dns_udp_queries_responded,
  SUM(IF(metricName = 'dns-tcp-queries', count, 0)) AS dns_tcp_queries_received,
  SUM(IF(metricName = 'dns-tcp-queries', count, 0)) AS dns_tcp_queries_responded,
   -- SRS metrics.
  SUM(IF(metricName = 'srs-dom-check', count, 0)) AS srs_dom_check,
  SUM(IF(metricName = 'srs-dom-create', count, 0)) AS srs_dom_create,
  SUM(IF(metricName = 'srs-dom-delete', count, 0)) AS srs_dom_delete,
  SUM(IF(metricName = 'srs-dom-info', count, 0)) AS srs_dom_info,
  SUM(IF(metricName = 'srs-dom-renew', count, 0)) AS srs_dom_renew,
  SUM(IF(metricName = 'srs-dom-rgp-restore-report', count, 0)) AS srs_dom_rgp_restore_report,
  SUM(IF(metricName = 'srs-dom-rgp-restore-request', count, 0)) AS srs_dom_rgp_restore_request,
  SUM(IF(metricName = 'srs-dom-transfer-approve', count, 0)) AS srs_dom_transfer_approve,
  SUM(IF(metricName = 'srs-dom-transfer-cancel', count, 0)) AS srs_dom_transfer_cancel,
  SUM(IF(metricName = 'srs-dom-transfer-query', count, 0)) AS srs_dom_transfer_query,
  SUM(IF(metricName = 'srs-dom-transfer-reject', count, 0)) AS srs_dom_transfer_reject,
  SUM(IF(metricName = 'srs-dom-transfer-request', count, 0)) AS srs_dom_transfer_request,
  SUM(IF(metricName = 'srs-dom-update', count, 0)) AS srs_dom_update,
  SUM(IF(metricName = 'srs-host-check', count, 0)) AS srs_host_check,
  SUM(IF(metricName = 'srs-host-create', count, 0)) AS srs_host_create,
  SUM(IF(metricName = 'srs-host-delete', count, 0)) AS srs_host_delete,
  SUM(IF(metricName = 'srs-host-info', count, 0)) AS srs_host_info,
  SUM(IF(metricName = 'srs-host-update', count, 0)) AS srs_host_update,
  SUM(IF(metricName = 'srs-cont-check', count, 0)) AS srs_cont_check,
  SUM(IF(metricName = 'srs-cont-create', count, 0)) AS srs_cont_create,
  SUM(IF(metricName = 'srs-cont-delete', count, 0)) AS srs_cont_delete,
  SUM(IF(metricName = 'srs-cont-info', count, 0)) AS srs_cont_info,
  SUM(IF(metricName = 'srs-cont-transfer-approve', count, 0)) AS srs_cont_transfer_approve,
  SUM(IF(metricName = 'srs-cont-transfer-cancel', count, 0)) AS srs_cont_transfer_cancel,
  SUM(IF(metricName = 'srs-cont-transfer-query', count, 0)) AS srs_cont_transfer_query,
  SUM(IF(metricName = 'srs-cont-transfer-reject', count, 0)) AS srs_cont_transfer_reject,
  SUM(IF(metricName = 'srs-cont-transfer-request', count, 0)) AS srs_cont_transfer_request,
  SUM(IF(metricName = 'srs-cont-update', count, 0)) AS srs_cont_update
  -- Cross join a list of all TLDs against TLD-specific metrics and then
  -- filter so that only metrics with that TLD or a NULL TLD are counted
  -- towards a given TLD.
FROM
  EXTERNAL_QUERY("projects/%PROJECT_ID%/locations/us/connections/%PROJECT_ID%-sql",
  '''SELECT
    "tld_name" AS tld
  FROM (
    SELECT
      "tld_name"
    FROM
      "Tld" AS t
    WHERE t.tld_type='REAL'
    ) AS "RealTlds";''')  AS RealTlds
CROSS JOIN(
  SELECT
  tld,
  metricName,
  count
  FROM
  (
  -- BEGIN INTERMEDIARY DATA SOURCES --
  -- Dummy data source to ensure all TLDs appear in report, even if
  -- they have no recorded metrics for the month.
  SELECT STRING(NULL) AS tld, STRING(NULL) AS metricName, 0 as count
  UNION ALL
  SELECT * FROM
  `%PROJECT_ID%.%CLOUD_SQL_ICANN_REPORTING_DATA_SET%.%REGISTRAR_OPERATING_STATUS_TABLE%`
  UNION ALL
  SELECT * FROM
  `%PROJECT_ID%.%ICANN_REPORTING_DATA_SET%.%DNS_COUNTS_TABLE%`
  UNION ALL
  SELECT * FROM
  `%PROJECT_ID%.%ICANN_REPORTING_DATA_SET%.%EPP_METRICS_TABLE%`
  UNION ALL
  SELECT * FROM
  `%PROJECT_ID%.%ICANN_REPORTING_DATA_SET%.%WHOIS_COUNTS_TABLE%`
  -- END INTERMEDIARY DATA SOURCES --
  )) AS TldMetrics
WHERE RealTlds.tld = TldMetrics.tld OR TldMetrics.tld IS NULL
GROUP BY tld
ORDER BY tld
