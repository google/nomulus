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

CREATE TABLE "HostHistory" (
   revision_id  bigserial NOT NULL,
    by_superuser boolean NOT NULL,
    creation_time timestamptz NOT NULL,
    other_registrar_id text,
    reason text,
    registrar_id text NOT NULL,
    requested_by_registrar boolean,
    client_transaction_id text,
    server_transaction_id text,
    type int4 NOT NULL,
    xml_bytes bytea,
    fully_qualified_host_name text,
    last_superordinate_change timestamptz,
    last_transfer_time timestamptz,
    referenced_entity text,
    superordinate_domain text,
    PRIMARY KEY (revision_id)
);

CREATE TABLE "HostHistory_domainTransactionRecords" (
   host_history_revision_id int8 NOT NULL,
    report_amount int4 NOT NULL,
    report_field int4 NOT NULL,
    reporting_time timestamptz NOT NULL,
    tld text NOT NULL,
    PRIMARY KEY (host_history_revision_id, report_amount, report_field, reporting_time, tld)
);

CREATE TABLE "HostHistory_inetAddresses" (
   host_history_revision_id int8 NOT NULL,
    inet_addresses bytea
);

CREATE INDEX IDXnxei34hfrt20dyxtphh6j25mo ON "HostHistory" (registrar_id);
CREATE INDEX IDXfg2nnjlujxo6cb9fha971bq2n ON "HostHistory" (creation_time);

ALTER TABLE IF EXISTS "HostHistory_domainTransactionRecords"
   ADD CONSTRAINT FKofp2dm4xd9my12aex1456rn7d
   FOREIGN KEY (host_history_revision_id)
   REFERENCES "HostHistory";
       
ALTER TABLE IF EXISTS "HostHistory_inetAddresses"
   ADD CONSTRAINT FKdxdtvupo2b6xlqsq4og4nlo7k
   FOREIGN KEY (host_history_revision_id)
   REFERENCES "HostHistory";
