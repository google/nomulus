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

create table "BillingEvent" (
    billing_event_id  bigserial not null,
    client_id text not null,
    event_time timestamptz not null,
    flags text[],
    reason text not null,
    target_id text not null,
    allocation_token_id text,
    billing_time timestamptz,
    cancellation_matching_billing_event_id int8,
    cost_amount numeric(19, 2),
    cost_currency text,
    period_years int4,
    synthetic_creation_time timestamptz,
    primary key (billing_event_id)
);

create table "BillingEventCancellation" (
    billing_event_id  bigserial not null,
    client_id text not null,
    event_time timestamptz not null,
    flags text[],
    reason text not null,
    target_id text not null,
    billing_time timestamptz,
    ref_one_time_id int8,
    ref_recurring_id int8,
    primary key (billing_event_id)
);

create table "RecurringBillingEvent" (
    billing_event_id  bigserial not null,
    client_id text not null,
    event_time timestamptz not null,
    flags text[],
    reason text not null,
    target_id text not null,
    recurrence_end_time timestamptz,
    recurrence_time_of_year text,
    primary key (billing_event_id)
);

create index IDX73l103vc5900ig3p4odf0cngt on "BillingEvent" (client_id);
create index IDX5yfbr88439pxw0v3j86c74fp8 on "BillingEvent" (event_time);
create index IDX6py6ocrab0ivr76srcd2okpnq on "BillingEvent" (billing_time);
create index IDXplxf9v56p0wg8ws6qsvd082hk on "BillingEvent" (synthetic_creation_time);
create index IDXhmv411mdqo5ibn4vy7ykxpmlv on "BillingEvent" (allocation_token_id);
create index IDX4y2cx3n6qb064347jhqk8e5vg on "BillingEventCancellation" (client_id);
create index IDXt94rv06pr8spwdkwed5bb0y4e on "BillingEventCancellation" (event_time);
create index IDX6pbkomdkdjup5ldsg4252vxgd on "BillingEventCancellation" (billing_time);
create index IDXg5ric3vcutj6p3d6qnycy6mp7 on "RecurringBillingEvent" (client_id);
create index IDX4napr9rx5yx9yfsnpnajytvu4 on "RecurringBillingEvent" (event_time);
create index IDXlm085j4hxe2x8yoh5ol9t4iyj on "RecurringBillingEvent" (recurrence_end_time);
create index IDXqlf2qj3tpds0ixqobrbysspn4 on "RecurringBillingEvent" (recurrence_time_of_year);

alter table if exists "BillingEvent"
   add constraint fk_billing_event_client_id
   foreign key (client_id)
   references "Registrar";

alter table if exists "BillingEvent"
   add constraint fk_billing_event_cancellation_matching_billing_event_id
   foreign key (cancellation_matching_billing_event_id)
   references "RecurringBillingEvent";

alter table if exists "BillingEventCancellation"
   add constraint fk_billing_event_cancellation_client_id
   foreign key (client_id)
   references "Registrar";

alter table if exists "BillingEventCancellation"
   add constraint fk_billing_event_cancellation_ref_one_time_id
   foreign key (ref_one_time_id)
   references "BillingEvent";

alter table if exists "BillingEventCancellation"
   add constraint fk_billing_event_cancellation_ref_recurring_id
   foreign key (ref_recurring_id)
   references "RecurringBillingEvent";

alter table if exists "RecurringBillingEvent"
   add constraint fk_recurring_billing_event_client_id
   foreign key (client_id)
   references "Registrar";
