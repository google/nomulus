-- Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

CREATE TABLE "User" (
    id int8 NOT NULL,
    email_address text NOT NULL,
    gaia_id text NOT NULL,
    registry_lock_password_hash text,
    registry_lock_password_salt text,
    global_role text NOT NULL,
    is_admin boolean NOT NULL,
    registrar_roles hstore NOT NULL,
    PRIMARY KEY(id)
);

CREATE INDEX user_gaia_id_idx ON "User" (gaia_id);
CREATE INDEX user_email_address_idx ON "User" (email_address);
