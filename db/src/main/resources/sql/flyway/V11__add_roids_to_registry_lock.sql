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

CREATE TABLE "RegistryLock_dependentRoids" (
   revision_id int8 NOT NULL,
    dependent_roid text NOT NULL,
    PRIMARY KEY (revision_id, dependent_roid)
);

ALTER TABLE IF EXISTS "RegistryLock_dependentRoids"
    ADD CONSTRAINT FKpx6ekkox5r1f72wd5axt1716d
    FOREIGN KEY (revision_id)
    REFERENCES "RegistryLock";

CREATE INDEX IDX7ktpb3kxgwbq0jnwfg9rca9js on "RegistryLock_dependentRoids" (dependent_roid);
