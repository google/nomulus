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

alter table if exists "SafeBrowsingThreat"
  rename to "Spec11ThreatMatch";

alter index if exists "safebrowsing_threat_registrar_id_idx"
  rename to "spec11threatmatch_registrar_id_idx";

alter index if exists "safebrowsing_threat_tld_idx"
  rename to "spec11threatmatch_tld_idx";

alter index if exists "safebrowsing_threat_check_date_idx"
  rename to "spec11threatmatch_check_date_idx";
