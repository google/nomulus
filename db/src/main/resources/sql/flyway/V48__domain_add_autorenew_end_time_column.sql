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

ALTER TABLE "Domain" ADD COLUMN autorenew_end_time timestamptz;
ALTER TABLE "DomainHistory" ADD COLUMN autorenew_end_time timestamptz;
CREATE INDEX IDXlrq7v63pc21uoh3auq6eybyhl ON "Domain" (autorenew_end_time);
