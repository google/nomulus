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

CREATE TABLE `RegistryLock` (
  revision_id BIGSERIAL NOT NULL,
  repo_id TEXT NOT NULL,
  domain_name TEXT NOT NULL,
  registrar_client_id TEXT NOT NULL,
  registrar_contact_id TEXT NOT NULL,
  lock_action TEXT NOT NULL,
  lock_status TEXT NOT NULL,
  creation_timestamp TIMESTAMPTZ NOT NULL,
  lock_timestamp TIMESTAMPTZ,
  unlock_timestamp TIMESTAMPTZ,
  verification_code TEXT UNIQUE NOT NULL,
  is_superuser BOOLEAN NOT NULL,
  PRIMARY KEY (revision_id)
);
