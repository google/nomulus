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

CREATE TABLE "AllocationToken" (
    token text not null,
    creation_time timestamptz NOT NULL,
    discount_fraction float8 NOT NULL,
    domain_name text,
    redemption_history_entry text,
    token_status_transitions hstore,
    token_type int4,
    primary key (token)
);

CREATE TABLE "AllocationToken_allowedClientIds" (
    allocation_token_token text NOT NULL,
    allowed_client_ids text
);

CREATE TABLE "AllocationToken_allowedTlds" (
    allocation_token_token text NOT NULL,
    allowed_tlds text
);


CREATE INDEX allocation_token_domain_name_idx ON "AllocationToken" (domain_name);

ALTER TABLE IF EXISTS "AllocationToken_allowedClientIds"
    ADD CONSTRAINT FKew0bx8dsv5c5by7xai2kqpneh
    FOREIGN KEY (allocation_token_token)
    REFERENCES "AllocationToken";

ALTER TABLE IF EXISTS "AllocationToken_allowedTlds"
    ADD CONSTRAINT FKok2xrui6m0gbyb9yjccoksl3o
    FOREIGN KEY (allocation_token_token)
    REFERENCES "AllocationToken";

ALTER TABLE IF EXISTS "AllocationToken"
    ADD CONSTRAINT fk_allocation_token_redemption_history_entry
    FOREIGN KEY (redemption_history_entry)
    REFERENCES "DomainHistory";
