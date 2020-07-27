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

ALTER TABLE "Domain" ADD COLUMN billing_recurrence_id int8;
ALTER TABLE "Domain" ADD COLUMN autorenew_poll_message_id int8;
ALTER TABLE "Domain" ADD COLUMN deletion_poll_message_id int8;

ALTER TABLE IF EXISTS "Domain"
   ADD CONSTRAINT fk_domain_billing_recurrence_id
   FOREIGN KEY (billing_recurrence_id)
   REFERENCES "BillingEvent";

ALTER TABLE IF EXISTS "Domain"
   ADD CONSTRAINT fk_domain_autorenew_poll_message_id
   FOREIGN KEY (autorenew_poll_message_id)
   REFERENCES "PollMessage";

ALTER TABLE IF EXISTS "Domain"
   ADD CONSTRAINT fk_domain_deletion_poll_message_id
   FOREIGN KEY (deletion_poll_message_id)
   REFERENCES "PollMessage";
