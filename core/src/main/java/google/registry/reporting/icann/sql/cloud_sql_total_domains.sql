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

  -- Determine the number of domains each registrar sponsors per tld.

  -- This is just the number of fullyQualifiedDomainNames under each
  -- tld-registrar pair.

SELECT
  tld,
  registrar_name,
  'TOTAL_DOMAINS' as metricName,
  COUNT(domain_name) as metricValue
FROM
  EXTERNAL_QUERY("projects/%PROJECT_ID%/locations/us/connections/%PROJECT_ID%-sql",
  '''SELECT
    registrar_name,
    registrar_id
  FROM "Registrar" AS r
  WHERE r.type='REAL' OR r.type='INTERNAL';''')
JOIN
  EXTERNAL_QUERY("projects/%PROJECT_ID%/locations/us/connections/%PROJECT_ID%-sql",
  '''SELECT
    tld,
    current_sponsor_registrar_id,
    domain_name
  FROM "Domain" AS d;''')
ON
  current_sponsor_registrar_id = registrar_id
GROUP BY tld, registrar_name
ORDER BY tld, registrar_name
