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

-- Some objects haven't been fully populated in SQL yet, so don't depend on them.
ALTER TABLE "Spec11ThreatMatch" DROP CONSTRAINT "fk_spec11_threat_match_domain_repo_id";
ALTER TABLE "Spec11ThreatMatch" DROP CONSTRAINT "fk_spec11_threat_match_registrar_id";
ALTER TABLE "Spec11ThreatMatch" DROP CONSTRAINT "fk_spec11_threat_match_tld";
