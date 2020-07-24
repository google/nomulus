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

create table "DelegationSignerData" (
    id  bigserial not null,
    algorithm int4 not null,
    digest bytea not null,
    digest_type int4 not null,
    key_tag int4 not null,
    primary key (id)
);

create table "DomainDsData" (
    domain_repo_id text not null,
    ds_data_id int8 not null,
    primary key (domain_repo_id, ds_data_id)
);

alter table if exists "DomainDsData"
   add constraint UK_ecl1ewlvuqp0cltbqx7vfp6by unique (ds_data_id);

alter table if exists "DomainDsData"
   add constraint FKl5ua6ggkmoa2h1fcqk0slgmi
   foreign key (ds_data_id)
   references "DelegationSignerData";

alter table if exists "DomainDsData"
   add constraint FKqdwyovl6ehp9sq6jkoomsfc5
   foreign key (domain_repo_id)
   references "Domain";
