#standardSQL
  -- Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

  -- This query gathers all Subdomains active within a given yearMonth
  -- and emits a row containing its fully qualified domain name
  -- [SLD].[TLD], the current registrar's name, and the current registrar's
  -- email address.

SELECT
  domain.fullyQualifiedDomainName AS domainName,
  domain.__key__.name AS domainRepoId,
  registrar.clientId AS clientId,
  COALESCE(registrar.emailAddress, '') AS registrarEmailAddress
FROM ( (
    SELECT
      __key__,
      fullyQualifiedDomainName,
      currentSponsorClientId,
      creationTime
    FROM
      `%PROJECT_ID%.%DATASTORE_EXPORT_DATASET%.%DOMAIN_BASE_TABLE%`
    WHERE
      -- Only include active registrations
      -- Registrations that are active (not deleted) will have null deletionTime
      -- because END_OF_TIME is an invalid timestamp in standardSQL
      (SAFE_CAST(deletionTime AS STRING) IS NULL
        OR deletionTime > CURRENT_TIMESTAMP)) AS domain
  JOIN (
    SELECT
      __key__.name AS clientId,
      emailAddress
    FROM
      `%PROJECT_ID%.%DATASTORE_EXPORT_DATASET%.%REGISTRAR_TABLE%`
    WHERE
      type = 'REAL') AS registrar
  ON
    domain.currentSponsorClientId = registrar.clientId)
ORDER BY
  creationTime DESC
