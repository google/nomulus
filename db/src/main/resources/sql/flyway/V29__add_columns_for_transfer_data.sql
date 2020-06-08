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
    add column transfer_gaining_poll_message_id int8,
    add column transfer_losing_poll_message_id int8,
    add column transfer_billing_cancellation_id int8,
    add column transfer_billing_event_id int8,
    add column transfer_billing_recurrence_id int8,
    add column transfer_autorenew_poll_message_id int8,
    add column transfer_renew_period_unit text,
    add column transfer_renew_period_value int4,
    add column transfer_client_txn_id text,
    add column transfer_server_txn_id text,
    add column transfer_registration_expiration_time timestamptz,
    add column transfer_gaining_registrar_id text,
    add column transfer_losing_registrar_id text,
    add column transfer_pending_expiration_time timestamptz,
    add column transfer_request_time timestamptz,
    add column transfer_status text;

alter table "Domain"
    add column transfer_gaining_poll_message_id int8,
    add column transfer_losing_poll_message_id int8,
    add column transfer_billing_cancellation_id int8,
    add column transfer_billing_event_id int8,
    add column transfer_billing_recurrence_id int8,
    add column transfer_autorenew_poll_message_id int8,
    add column transfer_renew_period_unit text,
    add column transfer_renew_period_value int4,
    add column transfer_client_txn_id text,
    add column transfer_server_txn_id text,
    add column transfer_registration_expiration_time timestamptz,
    add column transfer_gaining_registrar_id text,
    add column transfer_losing_registrar_id text,
    add column transfer_pending_expiration_time timestamptz,
    add column transfer_request_time timestamptz,
    add column transfer_status text;

alter table if exists "Contact"
   add constraint fk_contact_transfer_gaining_registrar_id
   foreign key (transfer_gaining_registrar_id)
   references "Registrar";

alter table if exists "Contact"
   add constraint fk_contact_transfer_losing_registrar_id
   foreign key (transfer_losing_registrar_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint fk_domain_transfer_gaining_registrar_id
   foreign key (transfer_gaining_registrar_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint fk_domain_transfer_losing_registrar_id
   foreign key (transfer_losing_registrar_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint fk_domain_transfer_billing_cancellation_id
   foreign key (transfer_billing_cancellation_id)
   references "BillingCancellation";

alter table if exists "Domain"
   add constraint fk_domain_transfer_billing_event_id
   foreign key (transfer_billing_event_id)
   references "BillingEvent";

alter table if exists "Domain"
   add constraint fk_domain_transfer_billing_recurrence_id
   foreign key (transfer_billing_recurrence_id)
   references "BillingRecurrence";
