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

alter table "Contact"
    add column transfer_server_approve_autorenew_event_id int8,
    add column transfer_server_approve_autorenew_poll_message_id int8,
    add column transfer_server_approve_billing_event_id int8,
    add column transfer_server_approve_entity_ids text[],
    add column transfer_period_unit int4,
    add column transfer_period_value int4,
    add column transfer_client_txn_id text,
    add column transfer_server_txn_id text,
    add column transfer_registration_expiration_time timestamptz,
    add column transfer_gaining_client_id text,
    add column transfer_losing_client_id text,
    add column transfer_pending_expiration_time timestamptz,
    add column transfer_request_time timestamptz,
    add column transfer_status int4;

alter table "Domain"
    add column transfer_server_approve_autorenew_event_id int8,
    add column transfer_server_approve_autorenew_poll_message_id int8,
    add column transfer_server_approve_billing_event_id int8,
    add column transfer_server_approve_entity_ids text[],
    add column transfer_period_unit int4,
    add column transfer_period_value int4,
    add column transfer_client_txn_id text,
    add column transfer_server_txn_id text,
    add column transfer_registration_expiration_time timestamptz,
    add column transfer_gaining_client_id text,
    add column transfer_losing_client_id text,
    add column transfer_pending_expiration_time timestamptz,
    add column transfer_request_time timestamptz,
    add column transfer_status int4;

alter table if exists "Contact"
   add constraint foreign_key_contact_transfer_gaining_client_id
   foreign key (transfer_gaining_client_id)
   references "Registrar";

alter table if exists "Contact"
   add constraint foreign_key_contact_transfer_losing_client_id
   foreign key (transfer_losing_client_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint foreign_key_domain_transfer_gaining_client_id
   foreign key (transfer_gaining_client_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint foreign_key_domain_transfer_losing_client_id
   foreign key (transfer_losing_client_id)
   references "Registrar";
