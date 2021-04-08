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

ALTER TABLE "ServerSecret" DROP CONSTRAINT "ServerSecret_pkey";
ALTER TABLE "ServerSecret" ADD COLUMN id bigint NOT NULL;
ALTER TABLE "ServerSecret" ADD PRIMARY KEY(id);

ALTER TABLE "TmchCrl" DROP CONSTRAINT "TmchCrl_pkey";
ALTER TABLE "TmchCrl" ADD COLUMN id bigint NOT NULL;
ALTER TABLE "TmchCrl" ADD PRIMARY KEY(id);
