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

-- We have to make this foreign key initially deferred even though
-- BillingEvent is saved before Domain (and deleted after).  If we don't, it
-- appears that Hibernate can sporadically introduce FK constraint failures
-- when updating a Domain to reference a new BillingEvent and then deleting
-- the old BillingEvent.  This may be due to the fact that this FK
-- relationships is not known to hibernate.
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_transfer_billing_event_id;
ALTER TABLE if exists "Domain"
   ADD CONSTRAINT fk_domain_transfer_billing_event_id
   FOREIGN KEY (transfer_billing_event_id)
   REFERENCES "BillingEvent"
   DEFERRABLE INITIALLY DEFERRED;

