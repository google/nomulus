-- Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

-- Note: we drop the not-null constraints from the history tables but we keep them in the
-- EPP resource tables since nothing inserted there should be null

ALTER TABLE "ContactHistory" ALTER COLUMN creation_registrar_id DROP NOT NULL;
ALTER TABLE "ContactHistory" ALTER COLUMN creation_time DROP NOT NULL;
ALTER TABLE "ContactHistory" ALTER COLUMN current_sponsor_registrar_id DROP NOT NULL;
ALTER TABLE "ContactHistory" ALTER COLUMN contact_repo_id DROP NOT NULL;
ALTER TABLE "ContactHistory" ALTER COLUMN history_reason DROP NOT NULL;

ALTER TABLE "DomainHistory" ALTER COLUMN creation_registrar_id DROP NOT NULL;
ALTER TABLE "DomainHistory" ALTER COLUMN creation_time DROP NOT NULL;
ALTER TABLE "DomainHistory" ALTER COLUMN current_sponsor_registrar_id DROP NOT NULL;
ALTER TABLE "DomainHistory" ALTER COLUMN history_reason DROP NOT NULL;

ALTER TABLE "HostHistory" ALTER COLUMN creation_registrar_id DROP NOT NULL;
ALTER TABLE "HostHistory" ALTER COLUMN creation_time DROP NOT NULL;
ALTER TABLE "HostHistory" ALTER COLUMN current_sponsor_registrar_id DROP NOT NULL;
ALTER TABLE "HostHistory" ALTER COLUMN history_reason DROP NOT NULL;
ALTER TABLE "HostHistory" ALTER COLUMN host_repo_id DROP NOT NULL;
