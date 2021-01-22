-- Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

alter table "Contact"
    add column if not exists "transfer_history_entry_id" int8;
alter table "Contact"
    add column if not exists "transfer_repo_id" text;
alter table "Contact"
    rename column "transfer_gaining_poll_message_id"
    to "transfer_poll_message_id_1";
alter table "Contact"
    rename column "transfer_losing_poll_message_id"
    to "transfer_poll_message_id_2";

alter table "ContactHistory"
    add column if not exists "transfer_history_entry_id" int8;
alter table "ContactHistory"
    add column if not exists "transfer_repo_id" text;
alter table "ContactHistory"
    rename column "transfer_gaining_poll_message_id"
    to "transfer_poll_message_id_1";
alter table "ContactHistory"
    rename column "transfer_losing_poll_message_id"
    to "transfer_poll_message_id_2";

alter table "Domain"
    add column if not exists "transfer_history_entry_id" int8;
alter table "Domain"
    add column if not exists "transfer_repo_id" text;
alter table "Domain"
    rename column "transfer_gaining_poll_message_id"
    to "transfer_poll_message_id_1";
alter table "Domain"
    rename column "transfer_losing_poll_message_id"
    to "transfer_poll_message_id_2";

alter table "DomainHistory"
    add column if not exists "transfer_history_entry_id" int8;
alter table "DomainHistory"
    add column if not exists "transfer_repo_id" text;
alter table "DomainHistory"
    rename column "transfer_gaining_poll_message_id"
    to "transfer_poll_message_id_1";
alter table "DomainHistory"
    rename column "transfer_losing_poll_message_id"
    to "transfer_poll_message_id_2";
