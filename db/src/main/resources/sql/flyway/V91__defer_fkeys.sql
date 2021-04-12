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

-- We require that *History table writes come after their corresponding
-- EppResource writes when replaying transactions from Datastore.
-- The alterations here serve to break cycles necessary to write the
-- resource first.

ALTER TABLE "BillingEvent" DROP CONSTRAINT fk_billing_event_domain_history;
ALTER TABLE "BillingEvent" ADD CONSTRAINT fk_billing_event_domain_history
   FOREIGN KEY (domain_repo_id, domain_history_revision_id)
   REFERENCES "DomainHistory"(domain_repo_id, history_revision_id)
   DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "BillingRecurrence" DROP CONSTRAINT fk_billing_recurrence_domain_history;
ALTER TABLE "BillingRecurrence" ADD CONSTRAINT fk_billing_recurrence_domain_history
   FOREIGN KEY (domain_repo_id, domain_history_revision_id)
   REFERENCES "DomainHistory"(domain_repo_id, history_revision_id)
   DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "BillingCancellation" DROP CONSTRAINT fk_billing_cancellation_domain_history;
ALTER TABLE "BillingCancellation" ADD CONSTRAINT fk_billing_cancellation_domain_history
   FOREIGN KEY (domain_repo_id, domain_history_revision_id)
   REFERENCES "DomainHistory"(domain_repo_id, history_revision_id)
   DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "PollMessage" DROP CONSTRAINT fk_poll_message_domain_history;
ALTER TABLE "PollMessage" ADD CONSTRAINT fk_poll_message_domain_history
   FOREIGN KEY (domain_repo_id, domain_history_revision_id)
   REFERENCES "DomainHistory"(domain_repo_id, history_revision_id)
   DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "PollMessage" DROP CONSTRAINT fk_poll_message_contact_history;
ALTER TABLE "PollMessage" ADD CONSTRAINT fk_poll_message_contact_history
   FOREIGN KEY (contact_repo_id, contact_history_revision_id)
   REFERENCES "ContactHistory"(contact_repo_id, history_revision_id)
   DEFERRABLE INITIALLY DEFERRED;
