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
   history_revision_id int8 NOT NULL,
    history_by_superuser boolean NOT NULL,
    history_registrar_id text NOT NULL,
    history_modification_time timestamptz NOT NULL,
    history_reason text NOT NULL,
    history_requested_by_registrar boolean NOT NULL,
    history_client_transaction_id text,
    history_server_transaction_id text,
    history_type text NOT NULL,
    history_xml_bytes bytea NOT NULL,
    fully_qualified_host_name text,
    inet_addresses text[],
    last_superordinate_change timestamptz,
    last_transfer_time timestamptz,
    superordinate_domain text,
    creation_registrar_id text NOT NULL,
    creation_time timestamptz NOT NULL,
    current_sponsor_registrar_id text NOT NULL,
    deletion_time timestamptz,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamptz,
    statuses text[],
    host_repo_id text NOT NULL,
    primary key (history_revision_id)
);

CREATE INDEX IDXfg2nnjlujxo6cb9fha971bq2n ON "HostHistory" (creation_time);
CREATE INDEX IDX1iy7njgb7wjmj9piml4l2g0qi ON "HostHistory" (history_registrar_id);
CREATE INDEX IDXj77pfwhui9f0i7wjq6lmibovj ON "HostHistory" (fully_qualified_host_name);
CREATE INDEX IDXknk8gmj7s47q56cwpa6rmpt5l ON "HostHistory" (history_type);
CREATE INDEX IDX67qwkjtlq5q8dv6egtrtnhqi7 ON "HostHistory" (history_modification_time);

ALTER TABLE IF EXISTS "HostHistory"
   ADD CONSTRAINT FK3d09knnmxrt6iniwnp8j2ykga
   FOREIGN KEY (history_registrar_id)
   REFERENCES "Registrar";

ALTER TABLE IF EXISTS "HostHistory"
   ADD CONSTRAINT FK_HostHistory_HostResource
   FOREIGN KEY (host_repo_id)
   REFERENCES "HostResource";

CREATE SEQUENCE public."history_id_sequence"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER TABLE ONLY public."HostHistory" ALTER COLUMN history_revision_id
   SET DEFAULT nextval('public."history_id_sequence"'::regclass);
