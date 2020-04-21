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
    creation_client_id text not null,
    creation_time timestamptz not null,
    current_sponsor_client_id text not null,
    deletion_time timestamptz,
    last_epp_update_client_id text,
    last_epp_update_time timestamptz,
    statuses text[],
    auth_info_repo_id text,
    auth_info_value text,
    contact_id text,
    disclose_types_addr text[],
    disclose_show_email boolean,
    disclose_show_fax boolean,
    disclose_mode_flag boolean,
    disclose_types_name text[],
    disclose_types_org text[],
    disclose_show_voice boolean,
    email text,
    fax_phone_extension text,
    fax_phone_number text,
    addr_i18n_city text,
    addr_i18n_country_code text,
    addr_i18n_state text,
    addr_i18n_street_line1 text,
    addr_i18n_street_line2 text,
    addr_i18n_street_line3 text,
    addr_i18n_zip text,
    addr_i18n_name text,
    addr_i18n_org text,
    addr_i18n_type text,
    last_transfer_time timestamptz,
    addr_local_city text,
    addr_local_country_code text,
    addr_local_state text,
    addr_local_street_line1 text,
    addr_local_street_line2 text,
    addr_local_street_line3 text,
    addr_local_zip text,
    addr_local_name text,
    addr_local_org text,
    addr_local_type text,
    search_name text,
    voice_phone_extension text,
    voice_phone_number text,
    primary key (repo_id)
);

create index IDX3y752kr9uh4kh6uig54vemx0l on "Contact" (creation_time);
create index IDXbn8t4wp85fgxjl8q4ctlscx55 on "Contact" (current_sponsor_client_id);
create index IDXn1f711wicdnooa2mqb7g1m55o on "Contact" (deletion_time);
create index IDX1p3esngcwwu6hstyua6itn6ff on "Contact" (search_name);

alter table if exists "Contact"
    add constraint UKoqd7n4hbx86hvlgkilq75olas unique (contact_id);

alter table "Domain" alter column creation_time set not null;
alter table "Domain" alter column creation_client_id set not null;
alter table "Domain" alter column current_sponsor_client_id set not null;

drop index IDX8ffrqm27qtj20jac056j7yq07;
create index IDXkjt9yaq92876dstimd93hwckh on "Domain" (current_sponsor_client_id);

alter table if exists "Contact"
   add constraint FK1sfyj7o7954prbn1exk7lpnoe
   foreign key (creation_client_id)
   references "Registrar";

alter table if exists "Contact"
   add constraint FK93c185fx7chn68uv7nl6uv2s0
   foreign key (current_sponsor_client_id)
   references "Registrar";

alter table if exists "Contact"
   add constraint FKmb7tdiv85863134w1wogtxrb2
   foreign key (last_epp_update_client_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint FK2jc69qyg2tv9hhnmif6oa1cx1
   foreign key (creation_client_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint FK2u3srsfbei272093m3b3xwj23
   foreign key (current_sponsor_client_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint FKjc0r9r5y1lfbt4gpbqw4wsuvq
   foreign key (last_epp_update_client_id)
   references "Registrar";
