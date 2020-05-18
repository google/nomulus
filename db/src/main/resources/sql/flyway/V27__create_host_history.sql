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
   id int8 NOT NULL,
    by_superuser boolean NOT NULL,
    registrar_id text NOT NULL,
    modification_time timestamptz NOT NULL,
    parent_v_key text NOT NULL,
    reason text NOT NULL,
    requested_by_registrar boolean NOT NULL,
    client_transaction_id text,
    server_transaction_id text,
    type int4 NOT NULL,
    xml_bytes bytea NOT NULL,
    fully_qualified_host_name text,
    last_superordinate_change timestamptz,
    last_transfer_time timestamptz,
    superordinate_domain bytea,
    creation_client_id text NOT NULL,
    creation_time timestamptz NOT NULL,
    current_sponsor_client_id text NOT NULL,
    deletion_time timestamptz,
    last_epp_update_client_id text,
    last_epp_update_time timestamptz,
    statuses text[],
    primary key (id)
);

CREATE TABLE "HostHistory_inetAddresses" (
   host_history_id int8 NOT NULL,
    inet_addresses bytea
);

CREATE INDEX IDXfg2nnjlujxo6cb9fha971bq2n ON "HostHistory" (creation_time);
CREATE INDEX IDXnxei34hfrt20dyxtphh6j25mo ON "HostHistory" (registrar_id);
CREATE INDEX IDXhancbub2w7c2rirfaeu4j9uh2 ON "HostHistory" (parent_v_key);

ALTER TABLE IF EXISTS "HostHistory_inetAddresses"
   ADD CONSTRAINT FK9svsf0mplnb9d7tdpl44lssvp
   FOREIGN KEY (host_history_id)
   REFERENCES "HostHistory";
