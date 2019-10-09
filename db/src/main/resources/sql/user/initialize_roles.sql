-- Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
--
-- Initializes roles and their privileges in the postgres database in Cloud SQL.
-- This script should run once under the **'postgres'** user before any other
-- roles or users are created.

-- Prevent backdoor grants through the implicit 'public' role.
REVOKE ALL PRIVILEGES ON SCHEMA public from public;

CREATE ROLE readonly;
GRANT CONNECT ON DATABASE postgres TO readonly;
GRANT USAGE ON SCHEMA public TO readonly;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO readonly;
ALTER DEFAULT PRIVILEGES
  IN SCHEMA public
  FOR USER postgres
  GRANT USAGE, SELECT ON SEQUENCES TO readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly;
ALTER DEFAULT PRIVILEGES
  IN SCHEMA public
  FOR USER postgres
  GRANT SELECT ON TABLES TO readonly;

CREATE ROLE readwrite;
GRANT CONNECT ON DATABASE postgres TO readwrite;
GRANT USAGE ON SCHEMA public TO readwrite;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO readwrite;
ALTER DEFAULT PRIVILEGES
  IN SCHEMA public
  FOR USER postgres
  GRANT USAGE, SELECT ON SEQUENCES TO readwrite;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO readwrite;
ALTER DEFAULT PRIVILEGES
  IN SCHEMA public
  FOR USER postgres
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO readwrite;
