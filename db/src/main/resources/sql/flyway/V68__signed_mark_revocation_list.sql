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

create table "SignedMarkRevocationEntry" (
   revision_id int8 not null,
    revocation_time timestamptz not null,
    smd_id text not null,
    primary key (revision_id, smd_id)
);

create table "SignedMarkRevocationList" (
   revision_id  bigserial not null,
    creation_time timestamptz,
    primary key (revision_id)
);

alter table if exists "SignedMarkRevocationEntry"
   add constraint FK5ivlhvs3121yx2li5tqh54u4
   foreign key (revision_id)
   references "SignedMarkRevocationList";
