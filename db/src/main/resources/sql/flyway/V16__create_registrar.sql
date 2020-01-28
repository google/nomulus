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
       client_id text not null,
        allowed_tlds text[],
        billing_account_map hstore,
        billing_identifier int8,
        block_premium_names boolean not null,
        client_certificate text,
        client_certificate_hash text,
        contacts_require_syncing boolean not null,
        creation_time timestamptz,
        drive_folder_id text,
        email_address text,
        failover_client_certificate text,
        failover_client_certificate_hash text,
        fax_number text,
        iana_identifier int8,
        icann_referral_email text,
        i18n_address_city text,
        i18n_address_country_code text,
        i18n_address_state text,
        i18n_address_street_line1 text,
        i18n_address_street_line2 text,
        i18n_address_street_line3 text,
        i18n_address_zip text,
        ip_address_whitelist text[],
        last_certificate_update_time timestamptz,
        last_update_time timestamptz,
        localized_address_city text,
        localized_address_country_code text,
        localized_address_state text,
        localized_address_street_line1 text,
        localized_address_street_line2 text,
        localized_address_street_line3 text,
        localized_address_zip text,
        password_hash text,
        phone_number text,
        phone_passcode text,
        po_number text,
        rdap_base_urls text[],
        registrar_name text not null,
        registry_lock_allowed boolean not null,
        password_salt text,
        state text,
        type text not null,
        url text,
        whois_server text,
        primary key (client_id)
    );

create index registrar_name_idx on "Registrar" (registrar_name);
create index registrar_iana_identifier_idx on "Registrar" (iana_identifier);
