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

ALTER TABLE "DomainHost" DROP CONSTRAINT FK_DomainHost_host_valid;
ALTER TABLE "DomainHost" ADD COLUMN ns_hosts text;
ALTER TABLE "DomainHost" DROP COLUMN ns_host_v_keys;
ALTER TABLE "DomainHost"
  ADD CONSTRAINT FK_DomainHost_host_valid
  FOREIGN KEY (ns_hosts)
  REFERENCES "HostResource";

