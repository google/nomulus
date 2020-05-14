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

CREATE TABLE "HostHistory" (
   revision_id  bigserial not null,
    by_superuser boolean not null,
    modification_time timestamptz not null,
    reason text not null,
    registrar_id text not null,
    repo_id text not null,
    requested_by_registrar boolean not null,
    client_transaction_id text,
    server_transaction_id text,
    type int4 not null,
    xml_bytes bytea not null,
    fully_qualified_host_name text,
    last_superordinate_change timestamptz,
    last_transfer_time timestamptz,
    superordinate_domain bytea,
    creation_client_id text not null,
    creation_time timestamptz not null,
    current_sponsor_client_id text not null,
    deletion_time timestamptz,
    last_epp_update_client_id text,
    last_epp_update_time timestamptz,
    statuses text[],
    primary key (revision_id)
);

CREATE TABLE "HostHistory_inetAddresses" (
   host_history_revision_id int8 NOT NULL,
    inet_addresses bytea
);

CREATE INDEX IDXfg2nnjlujxo6cb9fha971bq2n ON "HostHistory" (creation_time);
CREATE INDEX IDXnxei34hfrt20dyxtphh6j25mo ON "HostHistory" (registrar_id);
CREATE INDEX IDX3bep58si603pahajnxp41d9r1 ON "HostHistory" (repo_id);

ALTER TABLE IF EXISTS "HostHistory_inetAddresses"
   ADD CONSTRAINT FKdxdtvupo2b6xlqsq4og4nlo7k
   FOREIGN KEY (host_history_revision_id)
   REFERENCES "HostHistory";
