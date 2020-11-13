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

ALTER table "ContactHistory"
  ALTER column history_xml_bytes DROP NOT NULL,
  ALTER column history_requested_by_registrar DROP NOT NULL;

ALTER table "DomainHistory"
  ALTER column history_requested_by_registrar DROP NOT NULL;

ALTER table "HostHistory"
  ALTER column history_requested_by_registrar DROP NOT NULL;
