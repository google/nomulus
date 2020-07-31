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
    domain_repo_id text not null,
    key_tag int4 not null,
    algorithm int4 not null,
    digest bytea not null,
    digest_type int4 not null,
    primary key (domain_repo_id, key_tag)
);

create index IDXhlqqd5uy98cjyos72d81x9j95 on "DelegationSignerData" (domain_repo_id);

alter table if exists "DelegationSignerData"
   add constraint FKtr24j9v14ph2mfuw2gsmt12kq
   foreign key (domain_repo_id)
   references "Domain";
