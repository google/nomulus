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

ALTER TABLE IF EXISTS "DomainHost" RENAME TO "Domain_nsHosts";

CREATE TABLE "DomainHistory" (
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
    admin_contact text,
    auth_info_repo_id text,
    auth_info_value text,
    billing_contact text,
    domain_name text,
    idn_table_name text,
    last_transfer_time timestamptz,
    launch_notice_accepted_time timestamptz,
    launch_notice_expiration_time timestamptz,
    launch_notice_tcn_id text,
    launch_notice_validator_id text,
    registrant_contact text,
    registration_expiration_time timestamptz,
    smd_id text,
    subordinate_hosts text[],
    tech_contact text,
    tld text,
    transfer_billing_cancellation_id int8,
    transfer_billing_recurrence_id int8,
    transfer_autorenew_poll_message_id int8,
    transfer_billing_event_id int8,
    transfer_renew_period_unit text,
    transfer_renew_period_value int4,
    transfer_registration_expiration_time timestamptz,
    transfer_gaining_poll_message_id int8,
    transfer_losing_poll_message_id int8,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamptz,
    transfer_request_time timestamptz,
    transfer_status text,
    creation_registrar_id text NOT NULL,
    creation_time timestamptz NOT NULL,
    current_sponsor_registrar_id text NOT NULL,
    deletion_time timestamptz,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamptz,
    statuses text[],
    domain_repo_id text NOT NULL,
    PRIMARY KEY (history_revision_id)
);

CREATE TABLE "DomainHistory_nsHosts" (
   domain_history_history_revision_id int8 not null,
   ns_hosts text
);

CREATE INDEX IDXrh4xmrot9bd63o382ow9ltfig ON "DomainHistory" (creation_time);
CREATE INDEX IDXaro1omfuaxjwmotk3vo00trwm ON "DomainHistory" (history_registrar_id);
CREATE INDEX IDXsu1nam10cjes9keobapn5jvxj ON "DomainHistory" (history_type);
CREATE INDEX IDX6w3qbtgce93cal2orjg1tw7b7 ON "DomainHistory" (history_modification_time);

ALTER TABLE IF EXISTS "DomainHistory"
   ADD CONSTRAINT fk_domain_history_registrar_id
   FOREIGN KEY (history_registrar_id)
   REFERENCES "Registrar";

ALTER TABLE IF EXISTS "DomainHistory"
   ADD CONSTRAINT fk_domain_history_domain_repo_id
   FOREIGN KEY (domain_repo_id)
   REFERENCES "Domain";

ALTER TABLE ONLY public."DomainHistory" ALTER COLUMN history_revision_id
   SET DEFAULT nextval('public."history_id_sequence"'::regclass);

ALTER TABLE IF EXISTS "DomainHistory_nsHosts"
   ADD CONSTRAINT FK6b8eqdxwe3guc56tgpm89atx
   FOREIGN KEY (domain_history_history_revision_id)
   REFERENCES "DomainHistory";
