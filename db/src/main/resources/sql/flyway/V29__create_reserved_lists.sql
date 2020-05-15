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

    create table "ReservedLists" (
       tld_tld_id text not null,
        reserved_lists_v_keys_revision_id int8 not null,
        primary key (tld_tld_id, reserved_lists_v_keys_revision_id)
    );

    alter table if exists "ReservedLists"
           add constraint UK_odhn7pgtpcdi295pjchv2n4xs unique (reserved_lists_v_keys_revision_id);

 alter table if exists "ReservedLists"
       add constraint FKc6qbbn47pr8wa1eerdsgrqbht
       foreign key (reserved_lists_v_keys_revision_id)
       references "ReservedList";

    alter table if exists "ReservedLists"
       add constraint FKm3apblvy4jv12v0gn3o7y4pb0
       foreign key (tld_tld_id)
       references "Tld";
