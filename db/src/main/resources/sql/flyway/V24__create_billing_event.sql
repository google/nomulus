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

create sequence hibernate_sequence start 1 increment 1;

create table "BillingEvent" (
   type text not null,
    id  int8 not null,
    client_id text not null,
    event_time timestamptz not null,
    flags text[],
    reason text not null,
    target_id text not null,
    cancellation_billing_time timestamptz,
    cancellation_ref_onetime_id int8,
    cancellation_ref_recurring_id int8,
    modification_cost_amount numeric(19, 2),
    modification_cost_currency text,
    modification_description text,
    modification_ref_onetime_id int8,
    onetime_allocation_token_id text,
    onetime_billing_time timestamptz,
    onetime_cancellation_matching_id int8,
    onetime_cost_amount numeric(19, 2),
    onetime_cost_currency text,
    onetime_period_years int4,
    onetime_synthetic_creation_time timestamptz,
    recurrence_end_time timestamptz,
    recurrence_time_of_year text,
    primary key (id)
);

create index IDX73l103vc5900ig3p4odf0cngt on "BillingEvent" (client_id);
create index IDX5yfbr88439pxw0v3j86c74fp8 on "BillingEvent" (event_time);
create index IDX3m88tgkhnf7680useobc4au6v on "BillingEvent" (cancellation_billing_time);
create index IDXteelvp4hry8w7xyc0pycv8hr6 on "BillingEvent" (onetime_billing_time);
create index IDXfhbr41utchu6bpa43kn2ulx61 on "BillingEvent" (onetime_synthetic_creation_time);
create index IDXe8ipvfuacqd1s5144fiw3exln on "BillingEvent" (onetime_allocation_token_id);
create index IDX1vx9ue4mfn6466nuf7mrti04e on "BillingEvent" (recurrence_end_time);
create index IDXp1ud5nyyxfjms32qwv17772wk on "BillingEvent" (recurrence_time_of_year);

alter table if exists "BillingEvent"
   add constraint foreign_key_billing_event_cancellation_ref_onetime_id
   foreign key (cancellation_ref_onetime_id)
   references "BillingEvent";

alter table if exists "BillingEvent"
   add constraint foreign_key_billing_event_cancellation_ref_recurring_id
   foreign key (cancellation_ref_recurring_id)
   references "BillingEvent";

alter table if exists "BillingEvent"
   add constraint foreign_key_billing_event_modification_ref_onetime_id
   foreign key (modification_ref_onetime_id)
   references "BillingEvent";
