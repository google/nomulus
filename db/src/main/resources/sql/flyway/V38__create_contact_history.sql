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

CREATE TABLE "ContactHistory" (
   history_revision_id int8 NOT NULL,
    history_by_superuser boolean NOT NULL,
    history_registrar_id text,
    history_modification_time timestamptz NOT NULL,
    history_reason text NOT NULL,
    history_requested_by_registrar boolean NOT NULL,
    history_client_transaction_id text,
    history_server_transaction_id text,
    history_type text NOT NULL,
    history_xml_bytes bytea NOT NULL,
    auth_info_repo_id text,
    auth_info_value text,
    contact_id text,
    disclose_types_addr text[],
    disclose_show_email boolean,
    disclose_show_fax boolean,
    disclose_mode_flag boolean,
    disclose_types_name text[],
    disclose_types_org text[],
    disclose_show_voice boolean,
    email text,
    fax_phone_extension text,
    fax_phone_number text,
    addr_i18n_city text,
    addr_i18n_country_code text,
    addr_i18n_state text,
    addr_i18n_street_line1 text,
    addr_i18n_street_line2 text,
    addr_i18n_street_line3 text,
    addr_i18n_zip text,
    addr_i18n_name text,
    addr_i18n_org text,
    addr_i18n_type text,
    last_transfer_time timestamptz,
    addr_local_city text,
    addr_local_country_code text,
    addr_local_state text,
    addr_local_street_line1 text,
    addr_local_street_line2 text,
    addr_local_street_line3 text,
    addr_local_zip text,
    addr_local_name text,
    addr_local_org text,
    addr_local_type text,
    search_name text,
    transfer_gaining_poll_message_id int8,
    transfer_losing_poll_message_id int8,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamptz,
    transfer_request_time timestamptz,
    transfer_status text,
    voice_phone_extension text,
    voice_phone_number text,
    creation_registrar_id text NOT NULL,
    creation_time timestamptz NOT NULL,
    current_sponsor_registrar_id text NOT NULL,
    deletion_time timestamptz,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamptz,
    statuses text[],
    contact_repo_id text NOT NULL,
    primary key (history_revision_id)
);

create index IDXo1xdtpij2yryh0skxe9v91sep on "ContactHistory" (creation_time);
create index IDXhp33wybmb6tbpr1bq7ttwk8je on "ContactHistory" (history_registrar_id);
create index IDX9q53px6r302ftgisqifmc6put on "ContactHistory" (history_type);
create index IDXsudwswtwqnfnx2o1hx4s0k0g5 on "ContactHistory" (history_modification_time);

ALTER TABLE IF EXISTS "ContactHistory"
   ADD CONSTRAINT fk_contact_history_registrar_id
   FOREIGN KEY (history_registrar_id)
   REFERENCES "Registrar";

ALTER TABLE IF EXISTS "ContactHistory"
   ADD CONSTRAINT fk_contact_history_contact_repo_id
   FOREIGN KEY (contact_repo_id)
   REFERENCES "Contact";

ALTER TABLE ONLY public."ContactHistory" ALTER COLUMN history_revision_id
   SET DEFAULT nextval('public."history_id_sequence"'::regclass);
