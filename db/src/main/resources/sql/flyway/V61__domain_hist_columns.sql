-- Copyright 2020 The Nomulus Authors. All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http:--www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- These history ids are technically foreign keys, we don't want to constrain
-- them because they're temporary and we don't need to because they are
-- already indirectly constrained by the relationships with the
-- PollMessages/BillingEvents involved.
ALTER TABLE "Domain" ADD COLUMN billing_recurrence_history_id int8;
ALTER TABLE "Domain" ADD COLUMN autorenew_poll_message_history_id int8;
ALTER TABLE "Domain" ADD COLUMN deletion_poll_message_history_id int8;
ALTER TABLE "DomainHistory" ADD COLUMN billing_recurrence_history_id int8;
ALTER TABLE "DomainHistory" ADD COLUMN autorenew_poll_message_history_id int8;
ALTER TABLE "DomainHistory" ADD COLUMN deletion_poll_message_history_id int8;

-- Drop and re-add DomainHistory's Domain FK constraint to make it deferrable,
-- this breaks a cycle with Domain -> PollMessage|BillingEvent -> History.
ALTER TABLE "DomainHistory" DROP CONSTRAINT fk_domain_history_domain_repo_id;
ALTER TABLE "DomainHistory"
  ADD CONSTRAINT fk_domain_history_domain_repo_id
  FOREIGN KEY (domain_repo_id) REFERENCES "Domain"(repo_id)
  DEFERRABLE INITIALLY DEFERRED;

-- Same for PollMessage -> Domain, breaking Domain -> PollMessage -> Domain.
ALTER TABLE "PollMessage" DROP CONSTRAINT fk_poll_message_domain_repo_id;
ALTER TABLE "PollMessage"
  ADD CONSTRAINT fk_poll_message_domain_repo_id
  FOREIGN KEY (domain_repo_id) REFERENCES "Domain"(repo_id)
  DEFERRABLE INITIALLY DEFERRED;
