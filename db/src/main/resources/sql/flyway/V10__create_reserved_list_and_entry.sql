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

    create table "ReservedEntry" (
       revision_id int8 not null,
        comment text,
        reservation_type int4 not null,
        domain_label text not null,
        primary key (revision_id, domain_label)
    );

    create table "ReservedList" (
       revision_id  bigserial not null,
        creation_timestamp timestamptz not null,
        name text not null,
        should_publish boolean not null,
        primary key (revision_id)
    );

create index reservedlist_name_idx on "ReservedList" (name);

    alter table if exists "ReservedEntry"
       add constraint FKgq03rk0bt1hb915dnyvd3vnfc
       foreign key (revision_id)
       references "ReservedList";
