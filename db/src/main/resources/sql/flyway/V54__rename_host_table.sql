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


ALTER TABLE "HostResource" RENAME TO "Host";

ALTER TABLE "HostHistory" RENAME CONSTRAINT fk_hosthistory_hostresource TO fk_hosthistory_host;
ALTER TABLE "Host"
   RENAME CONSTRAINT fk_host_resource_superordinate_domain TO fk_host_superordinate_domain;
ALTER TABLE "Host" RENAME CONSTRAINT "HostResource_pkey" TO "Host_pkey";
