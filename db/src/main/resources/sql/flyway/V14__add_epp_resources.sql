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

    create table "DelegationSignerData" (
       key_tag int4 not null,
        algorithm int4 not null,
        digest bytea,
        digest_type int4 not null,
        primary key (key_tag)
    );

    create table "Domain" (
       repo_id text not null,
        creation_client_id text,
        creation_time timestamptz,
        current_sponsor_client_id text,
        deletion_time timestamptz,
        last_epp_update_client_id text,
        last_epp_update_time timestamptz,
        revisions bytea,
        auth_info_repo_id text,
        auth_info_value text,
        autorenew_billing_event bytea,
        autorenew_poll_message bytea,
        delete_poll_message bytea,
        fully_qualified_domain_name text,
        idn_table_name text,
        last_transfer_time timestamptz,
        launch_notice_accepted_time timestamptz,
        launch_notice_expiration_time timestamptz,
        launch_notice_tcn_id text,
        launch_notice_validator_id text,
        registration_expiration_time timestamptz,
        smd_id text,
        tld text,
        transfer_data_server_approve_autorenrew_event bytea,
        transfer_data_server_approve_autorenrew_poll_message bytea,
        transfer_data_server_approve_billing_event bytea,
        unit int4,
        value int4,
        client_transaction_id text,
        server_transaction_id text,
        transfer_data_registration_expiration_time timestamptz,
        gaining_client_id text,
        losing_client_id text,
        pending_transfer_expiration_time timestamptz,
        transfer_request_time timestamptz,
        transfer_status int4,
        primary key (repo_id)
    );

    create table "Domain_DelegationSignerData" (
       domain_base_repo_id text not null,
        ds_data_key_tag int4 not null,
        primary key (domain_base_repo_id, ds_data_key_tag)
    );

    create table "DomainBase_allContacts" (
       domain text not null,
        contact bytea not null,
        type int4
    );

    create table "Domain_GracePeriod" (
       domain_base_repo_id text not null,
        grace_periods_id int8 not null,
        primary key (domain_base_repo_id, grace_periods_id)
    );

    create table "DomainBase_nsHosts" (
       domain_base_repo_id text not null,
        ns_hosts bytea
    );

    create table "DomainBase_serverApproveEntities" (
       domain_base_repo_id text not null,
        transfer_data_server_approve_entities bytea
    );

    create table "DomainBase_subordinateHosts" (
       domain_base_repo_id text not null,
        subordinate_hosts text
    );

    create table "GracePeriod" (
       id  bigserial not null,
        billing_event_one_time bytea,
        billing_event_recurring bytea,
        client_id text,
        expiration_time timestamptz,
        type int4,
        primary key (id)
    );

    alter table if exists "Domain_GracePeriod"
       add constraint UK_4ps2u4y8i5r91wu2n1x2xea28 unique (grace_periods_id);

    alter table if exists "Domain_DelegationSignerData"
       add constraint FKho8wxowo3f4e688ehdl4wpni5
       foreign key (ds_data_key_tag)
       references "DelegationSignerData";

    alter table if exists "Domain_DelegationSignerData"
       add constraint FK2nvqbovvy5wasa8arhyhy8mge
       foreign key (domain_base_repo_id)
       references "Domain";

    alter table if exists "Domain_GracePeriod"
       add constraint FKny62h7k1nd3910rp56gdo5pfi
       foreign key (grace_periods_id)
       references "GracePeriod";

    alter table if exists "Domain_GracePeriod"
       add constraint FKkpor7amcdp7gwe0hp3obng6do
       foreign key (domain_base_repo_id)
       references "Domain";

    alter table if exists "DomainBase_allContacts"
       add constraint FKbh7x0hikqyo6jr50pj02tt6bu
       foreign key (domain)
       references "Domain";

    alter table if exists "DomainBase_nsHosts"
       add constraint FKow28763fcl1ilx8unxrfjtbja
       foreign key (domain_base_repo_id)
       references "Domain";

    alter table if exists "DomainBase_serverApproveEntities"
       add constraint FK7vuyqcsmcfvpv5648femoxien
       foreign key (domain_base_repo_id)
       references "Domain";

    alter table if exists "DomainBase_subordinateHosts"
       add constraint FKkva2lb57ri8qf39hthcej538k
       foreign key (domain_base_repo_id)
       references "Domain";

