-- Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

ALTER TABLE "BillingEvent" DROP CONSTRAINT fk_billing_event_domain_history;
ALTER TABLE "BillingEvent" DROP CONSTRAINT fk_billing_event_recurrence_history;
CREATE INDEX IDX77ceolnf7rok8ui957msmo6en ON "BillingEvent" (domain_repo_id, domain_history_revision_id);
CREATE INDEX IDXehp8ejwpbsooar0e8k32847m3 ON "BillingEvent" (domain_repo_id, recurrence_history_revision_id);
