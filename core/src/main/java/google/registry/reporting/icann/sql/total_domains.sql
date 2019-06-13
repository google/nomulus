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

  -- Determine the number of domains each registrar sponsors per tld.

  -- This is just the number of fullyQualifiedDomainNames under each
  -- tld-registrar pair.

SELECT
  tld,
  registrarName as registrar_name,
  'TOTAL_DOMAINS' as metricName,
  COUNT(fullyQualifiedDomainName) as metricValue
FROM
  `%PROJECT_ID%.%DATASTORE_EXPORT_DATA_SET%.%DOMAINBASE_TABLE%`
      AS domain_table
JOIN
   `%PROJECT_ID%.%DATASTORE_EXPORT_DATA_SET%.%REGISTRAR_TABLE%`
      AS registrar_table
ON
  currentSponsorClientId = registrar_table.__key__.name
WHERE
  registrar_table.type = 'REAL' OR registrar_table.type = 'INTERNAL'
GROUP BY tld, registrarName
ORDER BY tld, registrarName
