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

create table "GracePeriod" (
    id  bigserial not null,
    billing_event_id int8,
    billing_recurrence_id int8,
    registrar_id text not null,
    domain_repo_id text not null,
    expiration_time timestamptz not null,
    type text not null,
    primary key (id)
);

alter table if exists "GracePeriod"
   add constraint FK2mys4hojm6ev2g9tmy5aq6m7g
   foreign key (domain_repo_id)
   references "Domain";

alter table if exists "GracePeriod"
   add constraint fk_grace_period_billing_event_id
   foreign key (billing_event_id)
   references "BillingEvent";

alter table if exists "GracePeriod"
   add constraint fk_grace_period_billing_recurrence_id
   foreign key (billing_recurrence_id)
   references "BillingRecurrence";

create index IDXj1mtx98ndgbtb1bkekahms18w on "GracePeriod" (domain_repo_id);
