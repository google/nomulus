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

  -- Determine the number of referenced nameservers for a registrar's domains.

  -- We count the number of unique hosts under each tld-registrar combo by
  -- collecting all domains' listed hosts that were still valid at the
  -- end of the reporting month.

SELECT
  tld,
  registrarName AS registrar_name,
  'TOTAL_NAMESERVERS' AS metricName,
  COUNT(fullyQualifiedHostName) AS metricValue
FROM
  `%PROJECT_ID%.%DATASTORE_EXPORT_DATA_SET%.%HOSTRESOURCE_TABLE%` AS host_table
JOIN (
  SELECT
    __key__.name AS clientId,
    registrarName
  FROM
    `%PROJECT_ID%.%DATASTORE_EXPORT_DATA_SET%.%REGISTRAR_TABLE%`
  WHERE
    type = 'REAL'
    OR type = 'INTERNAL') AS registrar_table
ON
  currentSponsorClientId = registrar_table.clientId
JOIN (
  SELECT
    tld,
    hosts.name AS referencedHostName
  FROM
    `%PROJECT_ID%.%DATASTORE_EXPORT_DATA_SET%.%DOMAINBASE_TABLE%`,
    UNNEST(nsHosts) AS hosts
  WHERE creationTime <= TIMESTAMP("%LATEST_REPORT_TIME%")
  AND deletionTime > TIMESTAMP("%LATEST_REPORT_TIME%") ) AS domain_table
ON
  host_table.__key__.name = domain_table.referencedHostName
WHERE creationTime <= TIMESTAMP("%LATEST_REPORT_TIME%")
AND deletionTime > TIMESTAMP("%LATEST_REPORT_TIME%")
GROUP BY tld, registrarName
ORDER BY tld, registrarName

