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

create table "Contact" (
       repo_id text not null,
        creation_client_id text,
        creation_time timestamptz,
        current_sponsor_client_id text,
        deletion_time timestamptz,
        last_epp_update_client_id text,
        last_epp_update_time timestamptz,
        statuses text[],
        auth_info_repo_id text,
        auth_info_value text,
        contact_id text,
        addr text[],
        email_presence boolean,
        fax_presence boolean,
        flag boolean,
        name text[],
        org text[],
        voice_presence boolean,
        email text,
        fax_phone_extension text,
        fax_phone_number text,
        i18n_postal_info_address_city text,
        i18n_postal_info_address_country_code text,
        i18n_postal_info_address_state text,
        i18n_postal_info_address_street_line1 text,
        i18n_postal_info_address_street_line2 text,
        i18n_postal_info_address_street_line3 text,
        i18n_postal_info_address_zip text,
        i18n_postal_info_name text,
        i18n_postal_info_org text,
        i18n_postal_info_type int4,
        last_transfer_time timestamptz,
        localized_postal_info_address_city text,
        localized_postal_info_address_country_code text,
        localized_postal_info_address_state text,
        localized_postal_info_address_street_line1 text,
        localized_postal_info_address_street_line2 text,
        localized_postal_info_address_street_line3 text,
        localized_postal_info_address_zip text,
        localized_postal_info_name text,
        localized_postal_info_org text,
        localized_postal_info_type int4,
        search_name text,
        voice_phone_extension text,
        voice_phone_number text,
        primary key (repo_id)
    );

create index IDX3y752kr9uh4kh6uig54vemx0l on "Contact" (creation_time);
create index IDXbn8t4wp85fgxjl8q4ctlscx55 on "Contact" (current_sponsor_client_id);
create index IDXn1f711wicdnooa2mqb7g1m55o on "Contact" (deletion_time);
create index IDX1p3esngcwwu6hstyua6itn6ff on "Contact" (search_name);
