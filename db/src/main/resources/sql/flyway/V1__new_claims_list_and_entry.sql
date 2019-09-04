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

    create table "ClaimsEntry" (
       revision_id bigserial not null,
        claim_key varchar(255) not null,
        domain_label varchar(255) not null,
        primary key (revision_id, domain_label)
    );

    create table "ClaimsList" (
       revision_id  bigserial not null,
        creation_timestamp timestamptz not null,
        primary key (revision_id)
    );

    alter table "ClaimsEntry"
       add constraint FKlugn0q07ayrtar87dqi3vs3c8 
       foreign key (revision_id) 
       references "ClaimsList";
