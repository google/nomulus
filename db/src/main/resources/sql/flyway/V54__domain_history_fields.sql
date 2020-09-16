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


ALTER TABLE "DomainHistory" ADD COLUMN history_other_registrar_id text;
ALTER TABLE "DomainHistory" ADD COLUMN history_period_unit text;
ALTER TABLE "DomainHistory" ADD COLUMN history_period_value int4;

CREATE TABLE "DomainHistoryTransactionRecord" (
   domain_history_domain_repo_id text NOT NULL,
    domain_history_history_revision_id int8 NOT NULL,
    domain_transaction_records_id int8 NOT NULL,
    PRIMARY KEY (domain_history_domain_repo_id, domain_history_history_revision_id, domain_transaction_records_id)
);

CREATE TABLE  "DomainTransactionRecord" (
   id bigserial NOT NULL,
    report_amount int4 NOT NULL,
    report_field text NOT NULL,
    reporting_time timestamptz NOT NULL,
    tld text NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE IF EXISTS "DomainHistoryTransactionRecord" 
   ADD CONSTRAINT UK_i2obyd9bbrtfx2vm9ipwhvd0b UNIQUE (domain_transaction_records_id);

ALTER TABLE IF EXISTS "DomainHistoryTransactionRecord" 
   ADD CONSTRAINT FK8hf011yrspsho4nwgvydfyidr 
   FOREIGN KEY (domain_transaction_records_id) 
   REFERENCES "DomainTransactionRecord";

ALTER TABLE IF EXISTS "DomainHistoryTransactionRecord" 
   ADD CONSTRAINT FK80mpb1lv7m0fjif65jdcwais2 
   FOREIGN KEY (domain_history_domain_repo_id, domain_history_history_revision_id) 
   REFERENCES "DomainHistory";
