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

alter table "DelegationSignerData" drop constraint "DelegationSignerData_pkey";

alter table "DelegationSignerData"
    add constraint "DelegationSignerData_pkey"
    primary key (domain_repo_id, key_tag, algorithm, digest_type, digest);

create table "DomainDsDataHistory" (
    ds_data_history_revision_id int8 not null,
    algorithm int4 not null,
    digest bytea not null,
    digest_type int4 not null,
    domain_history_revision_id int8 not null,
    key_tag int4 not null,
    domain_repo_id text,
    primary key (ds_data_history_revision_id)
);

alter table if exists "DomainDsDataHistory"
    add constraint FKo4ilgyyfnvppbpuivus565i0j
    foreign key (domain_repo_id, domain_history_revision_id)
    references "DomainHistory";
