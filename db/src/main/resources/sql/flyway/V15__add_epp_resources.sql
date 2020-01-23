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

    create table "Domain" (
       repo_id text not null,
        creation_client_id text,
        creation_time timestamptz,
        current_sponsor_client_id text,
        deletion_time timestamptz,
        last_epp_update_client_id text,
        last_epp_update_time timestamptz,
        status text[],
        auth_info_repo_id text,
        auth_info_value text,
        fully_qualified_domain_name text,
        idn_table_name text,
        last_transfer_time timestamptz,
        launch_notice_accepted_time timestamptz,
        launch_notice_expiration_time timestamptz,
        launch_notice_tcn_id text,
        launch_notice_validator_id text,
        registration_expiration_time timestamptz,
        smd_id text,
        subordinate_hosts text[],
        tld text,
        primary key (repo_id)
    );

create index IDX8nr0ke9mrrx4ewj6pd2ag4rmr on "Domain" (creation_time);
create index IDX8ffrqm27qtj20jac056j7yq07 on "Domain" (current_sponsor_client_id);
create index IDX5mnf0wn20tno4b9do88j61klr on "Domain" (deletion_time);
create index IDX1rcgkdd777bpvj0r94sltwd5y on "Domain" (fully_qualified_domain_name);
create index IDXrwl38wwkli1j7gkvtywi9jokq on "Domain" (tld);
