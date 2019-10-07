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
--
-- Script to create a user with read-only permission to all tables.

-- Uncomment line below if user needs to be created:
-- CREATE USER :username ENCRYPTED PASSWORD :'password';
-- Uncomment line above and comment out line below if user has been created
-- from Cloud Dashboard:
ALTER USER :username NOCREATEDB NOCREATEROLE;
GRANT readonly TO :username;
