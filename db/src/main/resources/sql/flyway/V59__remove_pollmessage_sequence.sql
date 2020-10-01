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

alter table "PollMessage" alter column poll_message_id drop default;

drop sequence "PollMessage_poll_message_id_seq";

alter table "PollMessage" rename column "domain_revision_id" to "domain_history_revision_id";
alter table "PollMessage" rename column "contact_revision_id" to "contact_history_revision_id";
alter table "PollMessage" rename column "host_revision_id" to "host_history_revision_id";

alter table if exists "PollMessage"
   add constraint fk_poll_message_domain_history
   foreign key (domain_repo_id, domain_history_revision_id)
   references "DomainHistory";

alter table if exists "PollMessage"
   add constraint fk_poll_message_contact_history
   foreign key (contact_repo_id, contact_history_revision_id)
   references "ContactHistory";

alter table if exists "PollMessage"
   add constraint fk_poll_message_host_history
   foreign key (host_repo_id, host_history_revision_id)
   references "HostHistory";
