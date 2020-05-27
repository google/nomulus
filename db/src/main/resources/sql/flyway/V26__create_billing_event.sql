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

create table "BillingCancellation" (
    billing_cancellation_id  bigserial not null,
    client_id text not null,
    domain_history_revision_id int8 not null,
    domain_repo_id text not null,
    event_time timestamptz not null,
    flags text[],
    reason text not null,
    domain_name text not null,
    billing_time timestamptz,
    billing_event_id int8,
    billing_recurrence_id int8,
    primary key (billing_cancellation_id)
);

create table "BillingEvent" (
    billing_event_id  bigserial not null,
    client_id text not null,
    domain_history_revision_id int8 not null,
    domain_repo_id text not null,
    event_time timestamptz not null,
    flags text[],
    reason text not null,
    domain_name text not null,
    allocation_token_id text,
    billing_time timestamptz,
    cancellation_matching_billing_recurrence_id int8,
    cost_amount numeric(19, 2),
    cost_currency text,
    period_years int4,
    synthetic_creation_time timestamptz,
    primary key (billing_event_id)
);

create table "BillingRecurrence" (
    billing_recurrence_id  bigserial not null,
    client_id text not null,
    domain_history_revision_id int8 not null,
    domain_repo_id text not null,
    event_time timestamptz not null,
    flags text[],
    reason text not null,
    domain_name text not null,
    recurrence_end_time timestamptz,
    recurrence_time_of_year text,
    primary key (billing_recurrence_id)
);

create index IDXeokttmxtpq2hohcioe5t2242b on "BillingCancellation" (client_id);
create index IDX2exdfbx6oiiwnhr8j6gjpqt2j on "BillingCancellation" (event_time);
create index IDXqa3g92jc17e8dtiaviy4fet4x on "BillingCancellation" (billing_time);
create index IDX73l103vc5900ig3p4odf0cngt on "BillingEvent" (client_id);
create index IDX5yfbr88439pxw0v3j86c74fp8 on "BillingEvent" (event_time);
create index IDX6py6ocrab0ivr76srcd2okpnq on "BillingEvent" (billing_time);
create index IDXplxf9v56p0wg8ws6qsvd082hk on "BillingEvent" (synthetic_creation_time);
create index IDXhmv411mdqo5ibn4vy7ykxpmlv on "BillingEvent" (allocation_token_id);
create index IDXn898pb9mwcg359cdwvolb11ck on "BillingRecurrence" (client_id);
create index IDX6syykou4nkc7hqa5p8r92cpch on "BillingRecurrence" (event_time);
create index IDXp3usbtvk0v1m14i5tdp4xnxgc on "BillingRecurrence" (recurrence_end_time);
create index IDXjny8wuot75b5e6p38r47wdawu on "BillingRecurrence" (recurrence_time_of_year);

alter table if exists "BillingEvent"
   add constraint fk_billing_event_client_id
   foreign key (client_id)
   references "Registrar";

alter table if exists "BillingEvent"
   add constraint fk_billing_event_cancellation_matching_billing_recurrence_id
   foreign key (cancellation_matching_billing_recurrence_id)
   references "BillingRecurrence";

alter table if exists "BillingCancellation"
   add constraint fk_billing_cancellation_client_id
   foreign key (client_id)
   references "Registrar";

alter table if exists "BillingCancellation"
   add constraint fk_billing_cancellation_billing_event_id
   foreign key (billing_event_id)
   references "BillingEvent";

alter table if exists "BillingCancellation"
   add constraint fk_billing_cancellation_billing_recurrence_id
   foreign key (billing_recurrence_id)
   references "BillingRecurrence";

alter table if exists "BillingRecurrence"
   add constraint fk_billing_recurrence_client_id
   foreign key (client_id)
   references "Registrar";
