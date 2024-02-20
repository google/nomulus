-- Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

CREATE TABLE "UserUpdateHistory" (
 history_revision_id int8 NOT NULL,
 history_date_time timestamptz NOT NULL,
 history_method text NOT NULL,
 history_request_body text,
 history_type text NOT NULL,
 history_url text NOT NULL,
 email_address text NOT NULL,
 registry_lock_password_hash text,
 registry_lock_password_salt text,
 global_role text NOT NULL,
 is_admin boolean NOT NULL,
 registrar_roles hstore,
 update_timestamp timestamptz,
 history_acting_user text,
 PRIMARY KEY (history_revision_id)
);

CREATE INDEX IDXbjacjlm8ianc4kxxvamnu94k5 ON "UserUpdateHistory" (history_acting_user);

ALTER TABLE IF EXISTS "UserUpdateHistory"
    ADD CONSTRAINT FK1s7bopbl3pwrhv3jkkofnv3o0
    FOREIGN KEY (history_acting_user)
    REFERENCES "User" (email_address);
