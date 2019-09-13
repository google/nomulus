-- Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

    create table "RegistryLock" (
       revision_id  bigserial not null,
        action text not null,
        completion_timestamp timestamptz,
        creation_timestamp timestamptz not null,
        domain_name text not null,
        is_superuser boolean not null,
        registrar_id text not null,
        registrar_poc_id text,
        repo_id text not null,
        verification_code text not null,
        primary key (revision_id)
    );

    alter table if exists "RegistryLock" 
       add constraint idx_registry_lock_repo_id_revision_id unique (repo_id, revision_id);
