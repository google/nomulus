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

create table "RegistrarPoc" (
       email_address text not null,
        allowed_to_set_registry_lock_password boolean not null,
        fax_number text,
        gae_user_id text,
        name text,
        phone_number text,
        registry_lock_password_hash text,
        registry_lock_password_salt text,
        types text[],
        visible_in_domain_whois_as_abuse boolean not null,
        visible_in_whois_as_admin boolean not null,
        visible_in_whois_as_tech boolean not null,
        primary key (email_address)
    );

create index registrarpoc_gae_user_id_idx on "RegistrarPoc" (gae_user_id);
