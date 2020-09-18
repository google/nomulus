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

  create table "Tld" (
       tld_name text not null,
        add_grace_period_length interval not null,
        allowed_fully_qualified_host_names text[],
        allowed_registrant_contact_ids text[],
        anchor_tenant_add_grace_period_length interval not null,
        auto_renew_grace_period_length interval not null,
        automatic_transfer_length interval not null,
        claims_period_end timestamptz not null,
        create_billing_cost_amount numeric(19, 2),
        create_billing_cost_currency text,
        creation_time timestamptz not null,
        currency text not null,
        dns_paused boolean not null,
        dns_writers text[] not null,
        drive_folder_id text,
        eap_fee_schedule hstore not null,
        escrow_enabled boolean not null,
        invoicing_enabled boolean not null,
        lordn_username text,
        num_dns_publish_locks int4 not null,
        pending_delete_length interval not null,
        premium_list_name text,
        pricing_engine_class_name text,
        redemption_grace_period_length interval not null,
        registry_lock_or_unlock_cost_amount numeric(19, 2),
        registry_lock_or_unlock_cost_currency text,
        renew_billing_cost_transitions hstore not null,
        renew_grace_period_length interval not null,
        reserved_list_names text[] not null,
        restore_billing_cost_amount numeric(19, 2),
        restore_billing_cost_currency text,
        roid_suffix text,
        server_status_change_billing_cost_amount numeric(19, 2),
        server_status_change_billing_cost_currency text,
        tld_state_transitions hstore not null,
        tld_type text not null,
        tld_unicode text not null,
        transfer_grace_period_length interval not null,
        primary key (tld_name)
    );
