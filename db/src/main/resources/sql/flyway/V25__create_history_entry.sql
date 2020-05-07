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

CREATE TABLE "HistoryEntry" (
   id int8 NOT NULL,
    by_superuser boolean NOT NULL,
    client_id text NOT NULL,
    modification_time timestamptz NOT NULL,
    other_client_id text,
    parent_v_key text NOT NULL,
    unit int4,
    value int4,
    reason text,
    requested_by_registrar boolean NOT NULL,
    client_transaction_id text,
    server_transaction_id text,
    type int4 NOT NULL,
    xml_bytes bytea,
    PRIMARY KEY (id)
);

CREATE TABLE "HistoryEntry_domainTransactionRecords" (
   history_entry_id int8 NOT NULL,
    report_amount int4 NOT NULL,
    report_field int4 NOT NULL,
    reporting_time timestamptz NOT NULL,
    tld text NOT NULL,
    PRIMARY KEY (history_entry_id, report_amount, report_field, reporting_time, tld)
);

CREATE INDEX history_entry_modification_time_idx ON "HistoryEntry" (modification_time);
CREATE INDEX history_entry_client_id_idx ON "HistoryEntry" (client_id);

ALTER TABLE IF EXISTS "HistoryEntry_domainTransactionRecords"
   ADD CONSTRAINT FKfsidevsihqpa6jf96qo6kka57
   FOREIGN KEY (history_entry_id)
   REFERENCES "HistoryEntry";
