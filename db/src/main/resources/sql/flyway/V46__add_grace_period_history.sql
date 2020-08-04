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

create table "GracePeriodHistory" (
    id  bigserial not null,
    billing_event_id int8,
    billing_recurrence_id int8,
    registrar_id text not null,
    domain_repo_id text not null,
    expiration_time timestamptz not null,
    type text not null,
    history_revision_id int8,
    primary key (id)
);

alter table if exists "GracePeriodHistory"
   add constraint FKd0ac6a7xw17itq006bww30vnb
   foreign key (history_revision_id)
   references "DomainHistory";

alter table if exists "GracePeriodHistory"
   add constraint fk_grace_period_history_billing_event_id
   foreign key (billing_event_id)
   references "BillingEvent";

alter table if exists "GracePeriodHistory"
   add constraint fk_grace_period_history_billing_recurrence_id
   foreign key (billing_recurrence_id)
   references "BillingRecurrence";

create index IDXd01j17vrpjxaerxdmn8bwxs7s on "GracePeriodHistory" (domain_repo_id);
