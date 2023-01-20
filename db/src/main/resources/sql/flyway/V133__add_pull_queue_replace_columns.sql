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

ALTER TABLE "Domain"
    ADD COLUMN IF NOT EXISTS lordn_phase text DEFAULT 'NONE' NOT NULL;
ALTER TABLE "DomainHistory"
    ADD COLUMN IF NOT EXISTS lordn_phase text DEFAULT 'NONE' NOT NULL;
ALTER TABLE "HostHistory"
    ADD COLUMN IF NOT EXISTS dns_refresh_request_time timestamptz;
ALTER TABLE "Host"
    ADD COLUMN IF NOT EXISTS dns_refresh_request_time timestamptz;
CREATE INDEX IDXnjhib7v6fj7dhj5qydkefkl2u ON "Domain" (lordn_phase)
    WHERE lordn_phase != 'NONE';
CREATE INDEX IDX7wg0yn3wdux3xsc4pfaljqf08 ON "Host" (dns_refresh_request_time)
    WHERE dns_refresh_request_time is not null;
