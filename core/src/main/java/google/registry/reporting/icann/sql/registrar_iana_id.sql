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

  -- Gather a list of all tld-registrar pairs, with their IANA IDs.

  -- This establishes which registrars will appear in the reports.

SELECT
  allowed_tlds AS tld,
  registrarName AS registrar_name,
  ianaIdentifier AS iana_id
FROM
  `%PROJECT_ID%.%DATASTORE_EXPORT_DATA_SET%.%REGISTRAR_TABLE%`,
  UNNEST(allowedTlds) as allowed_tlds
WHERE (type = 'REAL' OR type = 'INTERNAL')
-- Filter out prober data
AND NOT ENDS_WITH(allowed_tlds, ".test")
ORDER BY tld, registrarName
