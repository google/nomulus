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

create table "SafeBrowsingThreat" (
       id  bigserial not null,
        check_date text not null,
        domain_name text not null,
        domain_repo_id text not null,
        registrar_id text not null,
        threat_type text not null,
        tld text not null,
        primary key (id)
    );

create index safebrowsing_threat_registrar_id_idx on "SafeBrowsingThreat" (registrar_id);
create index safebrowsing_threat_tld_idx on "SafeBrowsingThreat" (tld);
create index safebrowsing_threat_check_date_idx on "SafeBrowsingThreat" (check_date);

alter table if exists "SafeBrowsingThreat"
    add constraint fk_safebrowsing_threat_registrar_id
    foreign key (registrar_id)
    references "Registrar";

alter table if exists "SafeBrowsingThreat"
    add constraint fk_safebrowsing_threat_domain_repo_id
    foreign key (domain_repo_id)
    references "Domain";
