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
-- Script to create a user with read-write permission to all tables (except for
-- WRITE permissions to flyway_schema_history).

CREATE USER :username ENCRYPTED PASSWORD :'password';
GRANT CONNECT ON DATABASE postgres TO :username;
GRANT USAGE ON SCHEMA public TO :username;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :username;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :username;
REVOKE INSERT, UPDATE, DELETE ON TABLE public.flyway_schema_history FROM :username;
