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

  -- Determine the number of referenced nameservers for a registrar's domains.

  -- We count the number of unique hosts under each tld-registrar combo by
  -- collecting all domains' listed hosts that were still valid at the
  -- end of the reporting month.

SELECT
    tld,
    registrar_name,
    'TOTAL_NAMESERVERS' AS metricName,
    COUNT(host_name) AS metricValue
FROM
    EXTERNAL_QUERY("projects/domain-registry-alpha/locations/us/connections/domain-registry-alpha-sql",
    '''SELECT
        host_name,
        current_sponsor_registrar_id,
        creation_time AS host_creation_time,
        CASE
          WHEN deletion_time > to_timestamp('9999-12-31 23:59:59', 'YYYY-MM-DD HH24:MI:SS')
            THEN to_timestamp('9999-12-31 23:59:59', 'YYYY-MM-DD HH24:MI:SS')
          ELSE
            deletion_time
          END as host_deletion_time
    FROM "Host";''')
JOIN (
    SELECT
    registrar_id,
    registrar_name
    FROM
        EXTERNAL_QUERY("projects/domain-registry-alpha/locations/us/connections/domain-registry-alpha-sql", '''SELECT registrar_id, registrar_name FROM "Registrar" AS r WHERE r.type='REAL' OR r.type='INTERNAL';'''))
ON current_sponsor_registrar_id = registrar_id
JOIN (
    SELECT
        tld,
        referencedHostName
    FROM
        EXTERNAL_QUERY("projects/domain-registry-alpha/locations/us/connections/domain-registry-alpha-sql",
        '''SELECT
                repo_id,
                tld,
                creation_time AS domain_creation_time,
                CASE
                  WHEN deletion_time > to_timestamp('9999-12-31 23:59:59', 'YYYY-MM-DD HH24:MI:SS')
                    THEN to_timestamp('9999-12-31 23:59:59', 'YYYY-MM-DD HH24:MI:SS')
                  ELSE
                    deletion_time
                  END as domain_deletion_time
            FROM "Domain";''')
        JOIN
        EXTERNAL_QUERY("projects/domain-registry-alpha/locations/us/connections/domain-registry-alpha-sql", '''SELECT domain_repo_id, host_repo_id FROM "DomainHost";''')
        ON
        repo_id = domain_repo_id
        JOIN
        EXTERNAL_QUERY("projects/domain-registry-alpha/locations/us/connections/domain-registry-alpha-sql", '''SELECT repo_id as host_table_repo_id, host_name AS referencedHostName FROM "Host";''')
        ON
        host_table_repo_id = host_repo_id
        WHERE
        domain_creation_time <= TIMESTAMP("2017-09-30 23:59:59.999")
        AND domain_deletion_time > TIMESTAMP("2017-09-30 23:59:59.999"))
    ON host_name = referencedHostName
WHERE
    host_creation_time <= TIMESTAMP("2017-09-30 23:59:59.999")
    AND host_deletion_time > TIMESTAMP("2017-09-30 23:59:59.999")
GROUP BY tld, registrar_name
ORDER BY tld, registrar_name
