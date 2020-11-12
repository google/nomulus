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

alter table "GracePeriod" rename column "id" to "grace_period_id";

create table "GracePeriodHistory" (
    grace_period_history_revision_id int8 not null,
    billing_event_id int8,
    billing_event_history_id int8,
    billing_recurrence_id int8,
    billing_recurrence_history_id int8,
    registrar_id text not null,
    domain_repo_id text not null,
    expiration_time timestamptz not null,
    type text not null,
    domain_history_revision_id int8,
    grace_period_id int8 not null,
    primary key (grace_period_history_revision_id)
);

alter table if exists "GracePeriodHistory"
   add constraint FK7w3cx8d55q8bln80e716tr7b8
   foreign key (domain_repo_id, domain_history_revision_id)
   references "DomainHistory";

create index IDXd01j17vrpjxaerxdmn8bwxs7s on "GracePeriodHistory" (domain_repo_id);
