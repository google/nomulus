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

create table "PollMessage" (
    type text not null,
    poll_message_id  bigserial not null,
    registrar_id text not null,
    contact_repo_id text,
    contact_revision_id int8,
    domain_repo_id text,
    domain_revision_id int8,
    event_time timestamptz not null,
    host_repo_id text,
    host_revision_id int8,
    message text,
    transfer_response_contact_id text,
    transfer_response_domain_expiration_time timestamptz,
    transfer_response_domain_name text,
    pending_action_response_action_result boolean,
    pending_action_response_name_or_id text,
    pending_action_response_processed_date timestamptz,
    pending_action_response_client_txn_id text,
    pending_action_response_server_txn_id text,
    transfer_response_gaining_registrar_id text,
    transfer_response_losing_registrar_id text,
    transfer_response_pending_transfer_expiration_time timestamptz,
    transfer_response_transfer_request_time timestamptz,
    transfer_response_transfer_status text,
    autorenew_end_time timestamptz,
    autorenew_domain_name text,
    primary key (poll_message_id)
);

create index IDXe7wu46c7wpvfmfnj4565abibp on "PollMessage" (registrar_id);
create index IDXaydgox62uno9qx8cjlj5lauye on "PollMessage" (event_time);

alter table if exists "PollMessage"
   add constraint fk_poll_message_registrar_id
   foreign key (registrar_id)
   references "Registrar";

alter table if exists "PollMessage"
   add constraint fk_poll_message_contact_repo_id
   foreign key (contact_repo_id)
   references "Contact";

alter table if exists "PollMessage"
   add constraint fk_poll_message_domain_repo_id
   foreign key (domain_repo_id)
   references "Domain";

alter table if exists "PollMessage"
   add constraint fk_poll_message_host_repo_id
   foreign key (host_repo_id)
   references "HostResource";

alter table if exists "PollMessage"
   add constraint fk_poll_message_transfer_response_gaining_registrar_id
   foreign key (transfer_response_gaining_registrar_id)
   references "Registrar";

alter table if exists "PollMessage"
   add constraint fk_poll_message_transfer_response_losing_registrar_id
   foreign key (transfer_response_losing_registrar_id)
   references "Registrar";
