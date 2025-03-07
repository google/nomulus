-- Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

CREATE TABLE "ConsoleUpdateHistory" (
    revision_id bigint NOT NULL,
    modification_time timestamp with time zone NOT NULL,
    method text NOT NULL,
    type text NOT NULL,
    url text NOT NULL,
    description text,
    acting_user text NOT NULL,
    PRIMARY KEY (revision_id),
    CONSTRAINT fk_console_update_history_acting_user
        FOREIGN KEY(acting_user)
        REFERENCES "User" (email_address)
);

CREATE INDEX IF NOT EXISTS idx_console_update_history_acting_user
    ON "ConsoleUpdateHistory" (acting_user);
CREATE INDEX IF NOT EXISTS idx_console_update_history_type
    ON "ConsoleUpdateHistory" (type);
CREATE INDEX IF NOT EXISTS idx_console_update_history_modification_time
    ON "ConsoleUpdateHistory" (modification_time);
