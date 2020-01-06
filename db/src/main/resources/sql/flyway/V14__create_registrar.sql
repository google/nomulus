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

create table "Registrar" (
       client_identifier text not null,
        allowed_tlds text not null,
        billing_account_map text,
        billing_identifier int8,
        block_premium_names boolean not null,
        client_certificate text not null,
        client_certificate_hash text not null,
        contacts_require_syncing boolean not null,
        creation_time timestamptz not null,
        drive_folder_id text not null,
        email_address text not null,
        failover_client_certificate text not null,
        failover_client_certificate_hash text not null,
        fax_number text not null,
        iana_identifier int8,
        icann_referral_email text not null,
        inter_city text,
        inter_country_code text,
        inter_state text,
        inter_street text,
        inter_zip text,
        ip_address_whitelist text not null,
        last_certificate_update_time timestamptz not null,
        last_update_time timestamptz not null,
        local_city text,
        local_country_code text,
        local_state text,
        local_street text,
        local_zip text,
        parent bytea,
        password_hash text not null,
        phone_number text not null,
        phone_passcode text not null,
        po_number text,
        rdap_base_urls text not null,
        registrar_name text not null,
        registry_lock_allowed boolean not null,
        salt text not null,
        state int4 not null,
        type int4 not null,
        url text not null,
        whois_server text not null,
        primary key (client_identifier)
    );

create index registrar_name_idx on "Registrar" (registrar_name);
create index registrar_iana_identifier_idx on "Registrar" (iana_identifier);
